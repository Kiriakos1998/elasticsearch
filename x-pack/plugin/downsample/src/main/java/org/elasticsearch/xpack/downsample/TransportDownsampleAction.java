/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.downsample;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.admin.cluster.stats.MappingVisitor;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.SimpleBatchedExecutor;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexMetadata.DownsampleTaskStatus;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.cluster.routing.allocation.allocator.AllocationActionListener;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.TimeSeriesParams;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.aggregatemetric.mapper.AggregateDoubleMetricFieldMapper;
import org.elasticsearch.xpack.core.ClientHelper;
import org.elasticsearch.xpack.core.downsample.DownsampleAction;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.rollup.action.RollupShardPersistentTaskState;
import org.elasticsearch.xpack.core.rollup.action.RollupShardTask;
import org.elasticsearch.xpack.core.security.authz.AuthorizationServiceField;
import org.elasticsearch.xpack.core.security.authz.accesscontrol.IndicesAccessControl;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.elasticsearch.index.mapper.TimeSeriesParams.TIME_SERIES_METRIC_PARAM;
import static org.elasticsearch.xpack.core.ilm.DownsampleAction.DOWNSAMPLED_INDEX_PREFIX;

/**
 * The master downsample action that coordinates
 *  -  creating the downsample index
 *  -  instantiating {@link RollupShardIndexer}s to index downsample documents
 *  -  cleaning up state
 */
public class TransportDownsampleAction extends AcknowledgedTransportMasterNodeAction<DownsampleAction.Request> {

    private static final Logger logger = LogManager.getLogger(TransportDownsampleAction.class);

    private final Client client;
    private final IndicesService indicesService;
    private final ClusterService clusterService;
    private final MasterServiceTaskQueue<DownsampleClusterStateUpdateTask> taskQueue;
    private final MetadataCreateIndexService metadataCreateIndexService;
    private final IndexScopedSettings indexScopedSettings;
    private final ThreadContext threadContext;
    private final PersistentTasksService persistentTasksService;

    private static final Set<String> FORBIDDEN_SETTINGS = Set.of(
        IndexSettings.DEFAULT_PIPELINE.getKey(),
        IndexSettings.FINAL_PIPELINE.getKey(),
        IndexMetadata.INDEX_BLOCKS_WRITE_SETTING.getKey(),
        LifecycleSettings.LIFECYCLE_NAME_SETTING.getKey()
    );

    private static final Set<String> OVERRIDE_SETTINGS = Set.of(DataTier.TIER_PREFERENCE_SETTING.getKey());

    /**
     * This is the cluster state task executor for cluster state update actions.
     */
    private static final SimpleBatchedExecutor<DownsampleClusterStateUpdateTask, Void> STATE_UPDATE_TASK_EXECUTOR =
        new SimpleBatchedExecutor<>() {
            @Override
            public Tuple<ClusterState, Void> executeTask(DownsampleClusterStateUpdateTask task, ClusterState clusterState)
                throws Exception {
                return Tuple.tuple(task.execute(clusterState), null);
            }

            @Override
            public void taskSucceeded(DownsampleClusterStateUpdateTask task, Void unused) {
                task.listener.onResponse(AcknowledgedResponse.TRUE);
            }
        };

    @Inject
    public TransportDownsampleAction(
        Client client,
        IndicesService indicesService,
        ClusterService clusterService,
        TransportService transportService,
        ThreadPool threadPool,
        MetadataCreateIndexService metadataCreateIndexService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        IndexScopedSettings indexScopedSettings,
        PersistentTasksService persistentTasksService
    ) {
        super(
            DownsampleAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DownsampleAction.Request::new,
            indexNameExpressionResolver,
            ThreadPool.Names.SAME
        );
        this.client = new OriginSettingClient(client, ClientHelper.ROLLUP_ORIGIN);
        this.indicesService = indicesService;
        this.clusterService = clusterService;
        this.metadataCreateIndexService = metadataCreateIndexService;
        this.indexScopedSettings = indexScopedSettings;
        this.threadContext = threadPool.getThreadContext();
        this.taskQueue = clusterService.createTaskQueue("rollup", Priority.URGENT, STATE_UPDATE_TASK_EXECUTOR);
        this.persistentTasksService = persistentTasksService;
    }

    @Override
    protected void masterOperation(
        Task task,
        DownsampleAction.Request request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) {
        String sourceIndexName = request.getSourceIndex();

        final IndicesAccessControl indicesAccessControl = threadContext.getTransient(AuthorizationServiceField.INDICES_PERMISSIONS_KEY);
        if (indicesAccessControl != null) {
            final IndicesAccessControl.IndexAccessControl indexPermissions = indicesAccessControl.getIndexPermissions(sourceIndexName);
            if (indexPermissions != null) {
                boolean hasDocumentLevelPermissions = indexPermissions.getDocumentPermissions().hasDocumentLevelPermissions();
                boolean hasFieldLevelSecurity = indexPermissions.getFieldPermissions().hasFieldLevelSecurity();
                if (hasDocumentLevelPermissions || hasFieldLevelSecurity) {
                    listener.onFailure(
                        new ElasticsearchException(
                            "Rollup forbidden for index [" + sourceIndexName + "] with document level or field level security settings."
                        )
                    );
                    return;
                }
            }
        }
        // Assert source index exists
        IndexMetadata sourceIndexMetadata = state.getMetadata().index(sourceIndexName);
        if (sourceIndexMetadata == null) {
            listener.onFailure(new IndexNotFoundException(sourceIndexName));
            return;
        }

        // Assert source index is a time_series index
        if (IndexSettings.MODE.get(sourceIndexMetadata.getSettings()) != IndexMode.TIME_SERIES) {
            listener.onFailure(
                new ElasticsearchException(
                    "Rollup requires setting ["
                        + IndexSettings.MODE.getKey()
                        + "="
                        + IndexMode.TIME_SERIES
                        + "] for index ["
                        + sourceIndexName
                        + "]"
                )
            );
            return;
        }

        // Assert source index is read-only
        if (state.blocks().indexBlocked(ClusterBlockLevel.WRITE, sourceIndexName) == false) {
            listener.onFailure(
                new ElasticsearchException(
                    "Rollup requires setting [" + IndexMetadata.SETTING_BLOCKS_WRITE + " = true] for index [" + sourceIndexName + "]"
                )
            );
            return;
        }

        // Shortcircuit if target index has been downsampled:
        final String downsampleIndexName = request.getTargetIndex();
        IndexMetadata downsampleIndex = state.getMetadata().index(downsampleIndexName);
        if (downsampleIndex != null) {
            var downsampleStatus = IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(downsampleIndex.getSettings());
            if (downsampleStatus == DownsampleTaskStatus.UNKNOWN) {
                // This isn't a downsample index, so fail:
                listener.onFailure(new ResourceAlreadyExistsException(downsampleIndex.getIndex()));
                return;
            } else if (downsampleStatus == DownsampleTaskStatus.SUCCESS) {
                listener.onResponse(AcknowledgedResponse.TRUE);
                return;
            }
        }
        try {
            MetadataCreateIndexService.validateIndexName(downsampleIndexName, state);
        } catch (ResourceAlreadyExistsException e) {
            // ignore index already exists
        }

        // Downsample will perform the following tasks:
        // 1. Extract source index mappings
        // 2. Extract downsample config from index mappings
        // 3. Create the downsample index
        // 4. Run downsample indexer
        // 5. Make downsample index read-only and set replicas
        // 6. Refresh downsample index
        // 7. Mark downsample index as "completed successfully"
        // 8. Force-merge the downsample index to a single segment
        // At any point if there is an issue, delete the downsample index

        // 1. Extract source index mappings
        final TaskId parentTask = new TaskId(clusterService.localNode().getId(), task.getId());
        final GetMappingsRequest getMappingsRequest = new GetMappingsRequest().indices(sourceIndexName);
        getMappingsRequest.setParentTask(parentTask);
        client.admin().indices().getMappings(getMappingsRequest, ActionListener.wrap(getMappingsResponse -> {
            final Map<String, Object> sourceIndexMappings = getMappingsResponse.mappings()
                .entrySet()
                .stream()
                .filter(entry -> sourceIndexName.equals(entry.getKey()))
                .findFirst()
                .map(mappingMetadata -> mappingMetadata.getValue().sourceAsMap())
                .orElseThrow(() -> new IllegalArgumentException("No mapping found for rollup source index [" + sourceIndexName + "]"));

            // 2. Extract downsample config from index mappings
            final MapperService mapperService = indicesService.createIndexMapperServiceForValidation(sourceIndexMetadata);
            final CompressedXContent sourceIndexCompressedXContent = new CompressedXContent(sourceIndexMappings);
            mapperService.merge(MapperService.SINGLE_MAPPING_NAME, sourceIndexCompressedXContent, MapperService.MergeReason.INDEX_TEMPLATE);

            // Validate downsampling interval
            validateDownsamplingInterval(mapperService, request.getDownsampleConfig());

            final List<String> dimensionFields = new ArrayList<>();
            final List<String> metricFields = new ArrayList<>();
            final List<String> labelFields = new ArrayList<>();
            final TimeseriesFieldTypeHelper helper = new TimeseriesFieldTypeHelper.Builder(mapperService).build(
                request.getDownsampleConfig().getTimestampField()
            );
            MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
                if (helper.isTimeSeriesDimension(field, mapping)) {
                    dimensionFields.add(field);
                } else if (helper.isTimeSeriesMetric(field, mapping)) {
                    metricFields.add(field);
                } else if (helper.isTimeSeriesLabel(field, mapping)) {
                    labelFields.add(field);
                }
            });

            ActionRequestValidationException validationException = new ActionRequestValidationException();
            if (dimensionFields.isEmpty()) {
                validationException.addValidationError("Index [" + sourceIndexName + "] does not contain any dimension fields");
            }

            if (validationException.validationErrors().isEmpty() == false) {
                listener.onFailure(validationException);
                return;
            }

            final String mapping;
            try {
                mapping = createDownsampleIndexMapping(helper, request.getDownsampleConfig(), mapperService, sourceIndexMappings);
            } catch (IOException e) {
                listener.onFailure(e);
                return;
            }
            // 3. Create downsample index
            createDownsampleIndex(downsampleIndexName, sourceIndexMetadata, mapping, request, ActionListener.wrap(createIndexResp -> {
                if (createIndexResp.isAcknowledged()) {
                    performShardDownsampling(
                        request,
                        listener,
                        sourceIndexMetadata,
                        downsampleIndexName,
                        parentTask,
                        metricFields,
                        labelFields
                    );
                } else {
                    listener.onFailure(new ElasticsearchException("Failed to create rollup index [" + downsampleIndexName + "]"));
                }
            }, e -> {
                if (e instanceof ResourceAlreadyExistsException) {
                    performShardDownsampling(
                        request,
                        listener,
                        sourceIndexMetadata,
                        downsampleIndexName,
                        parentTask,
                        metricFields,
                        labelFields
                    );
                } else {
                    listener.onFailure(e);
                }
            }));
        }, listener::onFailure));
    }

    // 3. downsample index created or already exist (in case of retry). Run downsample indexer persistent task on each shard.
    private void performShardDownsampling(
        DownsampleAction.Request request,
        ActionListener<AcknowledgedResponse> listener,
        IndexMetadata sourceIndexMetadata,
        String downsampleIndexName,
        TaskId parentTask,
        List<String> metricFields,
        List<String> labelFields
    ) {
        final int numberOfShards = sourceIndexMetadata.getNumberOfShards();
        final Index sourceIndex = sourceIndexMetadata.getIndex();
        // NOTE: before we set the number of replicas to 0, as a result here we are
        // only dealing with primary shards.
        final AtomicInteger countDown = new AtomicInteger(numberOfShards);
        for (int shardNum = 0; shardNum < numberOfShards; shardNum++) {
            final ShardId shardId = new ShardId(sourceIndex, shardNum);
            final String persistentTaskId = createPersistentTaskId(
                downsampleIndexName,
                shardId,
                request.getDownsampleConfig().getInterval()
            );
            final DownsampleShardTaskParams params = createPersistentTaskParams(
                request.getDownsampleConfig(),
                sourceIndexMetadata,
                downsampleIndexName,
                metricFields,
                labelFields,
                shardId
            );
            Predicate<PersistentTasksCustomMetadata.PersistentTask<?>> predicate = runningTask -> {
                if (runningTask == null) {
                    // NOTE: don't need to wait if the persistent task completed and was removed
                    return true;
                }
                RollupShardPersistentTaskState runningPersistentTaskState = (RollupShardPersistentTaskState) runningTask.getState();
                return runningPersistentTaskState != null && runningPersistentTaskState.done();
            };
            var taskListener = new PersistentTasksService.WaitForPersistentTaskListener<>() {

                @Override
                public void onResponse(PersistentTasksCustomMetadata.PersistentTask<PersistentTaskParams> persistentTask) {
                    logger.info("Downsampling task [" + persistentTaskId + " completed for shard " + params.shardId());
                    if (countDown.decrementAndGet() == 0) {
                        logger.info("All downsampling tasks completed [" + numberOfShards + "]");
                        updateTargetIndexSettingStep(request, listener, sourceIndexMetadata, downsampleIndexName, parentTask);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    logger.error("error while waiting for downsampling persistent task", e);
                    listener.onFailure(e);
                }
            };
            persistentTasksService.sendStartRequest(
                persistentTaskId,
                RollupShardTask.TASK_NAME,
                params,
                ActionListener.wrap(
                    startedTask -> persistentTasksService.waitForPersistentTaskCondition(
                        startedTask.getId(),
                        predicate,
                        request.getWaitTimeout(),
                        taskListener
                    ),
                    e -> {
                        if (e instanceof ResourceAlreadyExistsException) {
                            logger.info("Task [" + persistentTaskId + "] already exists. Waiting.");
                            persistentTasksService.waitForPersistentTaskCondition(
                                persistentTaskId,
                                predicate,
                                request.getWaitTimeout(),
                                taskListener
                            );
                        } else {
                            listener.onFailure(new ElasticsearchException("Task [" + persistentTaskId + "] failed starting", e));
                        }
                    }
                )
            );
        }
    }

    // 4. Make downsample index read-only and set the correct number of replicas
    private void updateTargetIndexSettingStep(
        final DownsampleAction.Request request,
        final ActionListener<AcknowledgedResponse> listener,
        final IndexMetadata sourceIndexMetadata,
        final String downsampleIndexName,
        final TaskId parentTask
    ) {
        // 4. Make downsample index read-only and set the correct number of replicas
        final Settings.Builder settings = Settings.builder().put(IndexMetadata.SETTING_BLOCKS_WRITE, true);
        // Number of replicas had been previously set to 0 to speed up index population
        if (sourceIndexMetadata.getNumberOfReplicas() > 0) {
            settings.put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, sourceIndexMetadata.getNumberOfReplicas());
        }
        // Setting index.hidden has been initially set to true. We revert this to the value of the
        // source index
        if (sourceIndexMetadata.isHidden() == false) {
            if (sourceIndexMetadata.getSettings().keySet().contains(IndexMetadata.SETTING_INDEX_HIDDEN)) {
                settings.put(IndexMetadata.SETTING_INDEX_HIDDEN, false);
            } else {
                settings.putNull(IndexMetadata.SETTING_INDEX_HIDDEN);
            }
        }
        UpdateSettingsRequest updateSettingsReq = new UpdateSettingsRequest(settings.build(), downsampleIndexName);
        updateSettingsReq.setParentTask(parentTask);
        client.admin()
            .indices()
            .updateSettings(
                updateSettingsReq,
                new UpdateDownsampleIndexSettingsActionListener(listener, parentTask, downsampleIndexName, request.getWaitTimeout())
            );
    }

    private static DownsampleShardTaskParams createPersistentTaskParams(
        final DownsampleConfig downsampleConfig,
        final IndexMetadata sourceIndexMetadata,
        final String targetIndexName,
        final List<String> metricFields,
        final List<String> labelFields,
        final ShardId shardId
    ) {
        return new DownsampleShardTaskParams(
            downsampleConfig,
            targetIndexName,
            parseTimestamp(sourceIndexMetadata, IndexSettings.TIME_SERIES_START_TIME),
            parseTimestamp(sourceIndexMetadata, IndexSettings.TIME_SERIES_END_TIME),
            shardId,
            metricFields.toArray(new String[0]),
            labelFields.toArray(new String[0])
        );
    }

    private static long parseTimestamp(final IndexMetadata sourceIndexMetadata, final Setting<Instant> timestampSetting) {
        return OffsetDateTime.parse(sourceIndexMetadata.getSettings().get(timestampSetting.getKey()), DateTimeFormatter.ISO_DATE_TIME)
            .toInstant()
            .toEpochMilli();
    }

    private static String createPersistentTaskId(final String targetIndex, final ShardId shardId, final DateHistogramInterval interval) {
        return DOWNSAMPLED_INDEX_PREFIX + targetIndex + "-" + shardId.id() + "-" + interval;
    }

    @Override
    protected ClusterBlockException checkBlock(DownsampleAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }

    /**
     * This method creates the mapping for the downsample index, based on the
     * mapping (dimensions and metrics) from the source index, as well as the
     * downsample configuration.
     *
     * @param config the downsample configuration
     * @param sourceIndexMappings a map with the source index mapping
     * @return the mapping of the downsample index
     */
    public static String createDownsampleIndexMapping(
        final TimeseriesFieldTypeHelper helper,
        final DownsampleConfig config,
        final MapperService mapperService,
        final Map<String, Object> sourceIndexMappings
    ) throws IOException {
        final XContentBuilder builder = XContentFactory.jsonBuilder().startObject();

        addDynamicTemplates(builder);

        builder.startObject("properties");

        addTimestampField(config, sourceIndexMappings, builder);
        addMetricFields(helper, sourceIndexMappings, builder);

        builder.endObject(); // match initial startObject
        builder.endObject(); // match startObject("properties")

        final CompressedXContent mappingDiffXContent = CompressedXContent.fromJSON(
            XContentHelper.convertToJson(BytesReference.bytes(builder), false, XContentType.JSON)
        );
        return mapperService.merge(MapperService.SINGLE_MAPPING_NAME, mappingDiffXContent, MapperService.MergeReason.INDEX_TEMPLATE)
            .mappingSource()
            .uncompressed()
            .utf8ToString();
    }

    private static void addMetricFields(
        final TimeseriesFieldTypeHelper helper,
        final Map<String, Object> sourceIndexMappings,
        final XContentBuilder builder
    ) {
        MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
            if (helper.isTimeSeriesMetric(field, mapping)) {
                try {
                    addMetricFieldMapping(builder, field, mapping);
                } catch (IOException e) {
                    throw new ElasticsearchException("Error while adding metric for field [" + field + "]");
                }
            }
        });
    }

    private static void addTimestampField(
        final DownsampleConfig config,
        Map<String, Object> sourceIndexMappings,
        final XContentBuilder builder
    ) throws IOException {
        final String timestampField = config.getTimestampField();
        final String dateIntervalType = config.getIntervalType();
        final String dateInterval = config.getInterval().toString();
        final String timezone = config.getTimeZone();
        builder.startObject(timestampField);

        MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
            try {
                if (timestampField.equals(field)) {
                    final String timestampType = String.valueOf(mapping.get("type"));
                    builder.field("type", timestampType != null ? timestampType : DateFieldMapper.CONTENT_TYPE);
                    if (mapping.get("format") != null) {
                        builder.field("format", mapping.get("format"));
                    }
                }
            } catch (IOException e) {
                throw new ElasticsearchException("Unable to create timestamp field mapping for field [" + timestampField + "]", e);
            }
        });

        builder.startObject("meta")
            .field(dateIntervalType, dateInterval)
            .field(DownsampleConfig.TIME_ZONE, timezone)
            .endObject()
            .endObject();
    }

    private static void addMetricFieldMapping(final XContentBuilder builder, final String field, final Map<String, ?> fieldProperties)
        throws IOException {
        final TimeSeriesParams.MetricType metricType = TimeSeriesParams.MetricType.fromString(
            fieldProperties.get(TIME_SERIES_METRIC_PARAM).toString()
        );
        if (metricType == TimeSeriesParams.MetricType.COUNTER) {
            // For counters, we keep the same field type, because they store
            // only one value (the last value of the counter)
            builder.startObject(field).field("type", fieldProperties.get("type")).field(TIME_SERIES_METRIC_PARAM, metricType).endObject();
        } else {
            final List<String> supportedAggs = List.of(metricType.supportedAggs());
            // We choose max as the default metric
            final String defaultMetric = supportedAggs.contains("max") ? "max" : supportedAggs.get(0);
            builder.startObject(field)
                .field("type", AggregateDoubleMetricFieldMapper.CONTENT_TYPE)
                .stringListField(AggregateDoubleMetricFieldMapper.Names.METRICS, supportedAggs)
                .field(AggregateDoubleMetricFieldMapper.Names.DEFAULT_METRIC, defaultMetric)
                .field(TIME_SERIES_METRIC_PARAM, metricType)
                .endObject();
        }
    }

    private static void validateDownsamplingInterval(MapperService mapperService, DownsampleConfig config) {
        MappedFieldType timestampFieldType = mapperService.fieldType(config.getTimestampField());
        assert timestampFieldType != null : "Cannot find timestamp field [" + config.getTimestampField() + "] in the mapping";
        ActionRequestValidationException e = new ActionRequestValidationException();

        Map<String, String> meta = timestampFieldType.meta();
        if (meta.isEmpty() == false) {
            String interval = meta.get(config.getIntervalType());
            if (interval != null) {
                try {
                    DownsampleConfig sourceConfig = new DownsampleConfig(new DateHistogramInterval(interval));
                    DownsampleConfig.validateSourceAndTargetIntervals(sourceConfig, config);
                } catch (IllegalArgumentException exception) {
                    e.addValidationError("Source index is a downsampled index. " + exception.getMessage());
                }
            }

            // Validate that timezones match
            String sourceTimezone = meta.get(DownsampleConfig.TIME_ZONE);
            if (sourceTimezone != null && sourceTimezone.equals(config.getTimeZone()) == false) {
                e.addValidationError(
                    "Source index is a downsampled index. Downsampling timezone ["
                        + config.getTimeZone()
                        + "] cannot be different than the source index timezone ["
                        + sourceTimezone
                        + "]."
                );
            }

            if (e.validationErrors().isEmpty() == false) {
                throw e;
            }
        }
    }

    /**
     * Copy index settings from the source index to the downsample index. Settings that
     * have already been set in the downsample index will not be overridden.
     */
    static IndexMetadata.Builder copyIndexMetadata(
        final IndexMetadata sourceIndexMetadata,
        final IndexMetadata downsampleIndexMetadata,
        final IndexScopedSettings indexScopedSettings
    ) {
        // Copy index settings from the source index, but do not override the settings
        // that already have been set in the downsample index
        final Settings.Builder targetSettings = Settings.builder().put(downsampleIndexMetadata.getSettings());
        for (final String key : sourceIndexMetadata.getSettings().keySet()) {
            final Setting<?> setting = indexScopedSettings.get(key);
            if (setting == null) {
                assert indexScopedSettings.isPrivateSetting(key) : "expected [" + key + "] to be private but it was not";
            } else if (setting.getProperties().contains(Setting.Property.NotCopyableOnResize)) {
                // we leverage the NotCopyableOnResize setting property for downsample, because
                // the same rules with resize apply
                continue;
            }
            // Do not copy index settings which are valid for the source index but not for the target index
            if (FORBIDDEN_SETTINGS.contains(key)) {
                continue;
            }

            if (OVERRIDE_SETTINGS.contains(key)) {
                targetSettings.put(key, sourceIndexMetadata.getSettings().get(key));
            }
            // Do not override settings that have already been set in the downsample index.
            if (targetSettings.keys().contains(key)) {
                continue;
            }

            targetSettings.copy(key, sourceIndexMetadata.getSettings());
        }

        /*
         * Add the source index name and UUID to the downsample index metadata.
         * If the source index is a downsample index, we will add the name and UUID
         * of the first index that we initially rolled up.
         */
        if (IndexMetadata.INDEX_DOWNSAMPLE_SOURCE_UUID.exists(sourceIndexMetadata.getSettings()) == false
            || IndexMetadata.INDEX_DOWNSAMPLE_SOURCE_NAME.exists(sourceIndexMetadata.getSettings()) == false) {
            Index sourceIndex = sourceIndexMetadata.getIndex();
            targetSettings.put(IndexMetadata.INDEX_DOWNSAMPLE_SOURCE_NAME.getKey(), sourceIndex.getName())
                .put(IndexMetadata.INDEX_DOWNSAMPLE_SOURCE_UUID.getKey(), sourceIndex.getUUID());
        }

        return IndexMetadata.builder(downsampleIndexMetadata).settings(targetSettings);
    }

    /**
     * Configure the dynamic templates to always map strings to the keyword field type.
     */
    private static void addDynamicTemplates(final XContentBuilder builder) throws IOException {
        builder.startArray("dynamic_templates")
            .startObject()
            .startObject("strings")
            .field("match_mapping_type", "string")
            .startObject("mapping")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .endArray();
    }

    private void createDownsampleIndex(
        String downsampleIndexName,
        IndexMetadata sourceIndexMetadata,
        String mapping,
        DownsampleAction.Request request,
        ActionListener<AcknowledgedResponse> listener
    ) {
        /*
         * When creating the downsample index, we copy the index.number_of_shards from source index,
         * and we set the index.number_of_replicas to 0, to avoid replicating the index being built.
         * Also, we set the index.refresh_interval to -1.
         * We will set the correct number of replicas and refresh the index later.
         *
         * We should note that there is a risk of losing a node during the downsample process. In this
         * case downsample will fail.
         */
        Settings.Builder builder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_HIDDEN, true)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, sourceIndexMetadata.getNumberOfShards())
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), "-1")
            .put(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.getKey(), DownsampleTaskStatus.STARTED);
        if (sourceIndexMetadata.getSettings().hasValue(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())) {
            builder.put(
                MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey(),
                sourceIndexMetadata.getSettings().get(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())
            );
        }

        CreateIndexClusterStateUpdateRequest createIndexClusterStateUpdateRequest = new CreateIndexClusterStateUpdateRequest(
            "downsample",
            downsampleIndexName,
            downsampleIndexName
        ).settings(builder.build()).mappings(mapping).waitForActiveShards(ActiveShardCount.ONE);
        var delegate = new AllocationActionListener<>(listener, threadPool.getThreadContext());
        taskQueue.submitTask("create-downsample-index [" + downsampleIndexName + "]", new DownsampleClusterStateUpdateTask(listener) {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                return metadataCreateIndexService.applyCreateIndexRequest(
                    currentState,
                    createIndexClusterStateUpdateRequest,
                    true,
                    // Copy index metadata from source index to downsample index
                    (builder, indexMetadata) -> builder.put(copyIndexMetadata(sourceIndexMetadata, indexMetadata, indexScopedSettings)),
                    delegate.reroute()
                );
            }
        }, request.masterNodeTimeout());
    }

    /**
     * A specialized cluster state update task that always takes a listener handling an
     * AcknowledgedResponse, as all template actions have simple acknowledged yes/no responses.
     */
    abstract static class DownsampleClusterStateUpdateTask implements ClusterStateTaskListener {
        final ActionListener<AcknowledgedResponse> listener;

        DownsampleClusterStateUpdateTask(ActionListener<AcknowledgedResponse> listener) {
            this.listener = listener;
        }

        public abstract ClusterState execute(ClusterState currentState) throws Exception;

        @Override
        public void onFailure(Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Refreshes the downsample target index
     */
    class UpdateDownsampleIndexSettingsActionListener implements ActionListener<AcknowledgedResponse> {
        final ActionListener<AcknowledgedResponse> listener;
        final TaskId parentTask;
        final String downsampleIndexName;
        final TimeValue timeout;

        UpdateDownsampleIndexSettingsActionListener(
            final ActionListener<AcknowledgedResponse> listener,
            final TaskId parentTask,
            final String downsampleIndexName,
            final TimeValue timeout
        ) {
            this.listener = listener;
            this.parentTask = parentTask;
            this.downsampleIndexName = downsampleIndexName;
            this.timeout = timeout;
        }

        @Override
        public void onResponse(final AcknowledgedResponse response) {
            final RefreshRequest request = new RefreshRequest(downsampleIndexName);
            request.setParentTask(parentTask);
            client.admin()
                .indices()
                .refresh(request, new RefreshDownsampleIndexActionListener(listener, parentTask, downsampleIndexName, timeout));
        }

        @Override
        public void onFailure(Exception e) {
            listener.onFailure(e);
        }

    }

    /**
     * Updates the downsample target index metadata (task status)
     */
    class RefreshDownsampleIndexActionListener implements ActionListener<RefreshResponse> {

        private final ActionListener<AcknowledgedResponse> actionListener;
        private final TaskId parentTask;
        private final String downsampleIndexName;
        private final TimeValue timeout;

        RefreshDownsampleIndexActionListener(
            final ActionListener<AcknowledgedResponse> actionListener,
            TaskId parentTask,
            final String downsampleIndexName,
            final TimeValue timeout
        ) {
            this.actionListener = actionListener;
            this.parentTask = parentTask;
            this.downsampleIndexName = downsampleIndexName;
            this.timeout = timeout;
        }

        @Override
        public void onResponse(final RefreshResponse response) {
            if (response.getFailedShards() != 0) {
                logger.info("Post refresh failed [{}],{}", downsampleIndexName, Strings.toString(response));
            }
            // Mark downsample index as "completed successfully" ("index.downsample.status": "success")
            taskQueue.submitTask(
                "update-downsample-metadata [" + downsampleIndexName + "]",
                new DownsampleClusterStateUpdateTask(new ForceMergeActionListener(parentTask, downsampleIndexName, actionListener)) {

                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        final Metadata metadata = currentState.metadata();
                        final IndexMetadata downsampleIndex = metadata.index(metadata.index(downsampleIndexName).getIndex());
                        if (IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(downsampleIndex.getSettings()) == DownsampleTaskStatus.SUCCESS) {
                            return currentState;
                        }

                        final Metadata.Builder metadataBuilder = Metadata.builder(metadata);
                        metadataBuilder.updateSettings(
                            Settings.builder()
                                .put(downsampleIndex.getSettings())
                                .put(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.getKey(), DownsampleTaskStatus.SUCCESS)
                                .build(),
                            downsampleIndexName
                        );
                        return ClusterState.builder(currentState).metadata(metadataBuilder.build()).build();
                    }
                },
                timeout
            );
        }

        @Override
        public void onFailure(Exception e) {
            actionListener.onFailure(e);
        }

    }

    /**
     * Triggers a force merge operation on the downsample target index
     */
    class ForceMergeActionListener implements ActionListener<AcknowledgedResponse> {

        final ActionListener<AcknowledgedResponse> actionListener;
        private final TaskId parentTask;
        private final String downsampleIndexName;

        ForceMergeActionListener(
            final TaskId parentTask,
            final String downsampleIndexName,
            final ActionListener<AcknowledgedResponse> onFailure
        ) {
            this.parentTask = parentTask;
            this.downsampleIndexName = downsampleIndexName;
            this.actionListener = onFailure;
        }

        @Override
        public void onResponse(final AcknowledgedResponse response) {
            /*
             * At this point downsample index has been created
             * successfully even force merge fails.
             * So, we should not fail the downsmaple operation
             */
            ForceMergeRequest request = new ForceMergeRequest(downsampleIndexName);
            request.maxNumSegments(1);
            request.setParentTask(parentTask);
            client.admin()
                .indices()
                .forceMerge(request, ActionListener.wrap(mergeIndexResp -> actionListener.onResponse(AcknowledgedResponse.TRUE), t -> {
                    /*
                     * At this point downsampel index has been created
                     * successfully even force merge fails.
                     * So, we should not fail the downsample operation
                     */
                    logger.error("Failed to force-merge " + "downsample index [" + downsampleIndexName + "]", t);
                    actionListener.onResponse(AcknowledgedResponse.TRUE);
                }));
        }

        @Override
        public void onFailure(Exception e) {
            this.actionListener.onFailure(e);
        }

    }
}
