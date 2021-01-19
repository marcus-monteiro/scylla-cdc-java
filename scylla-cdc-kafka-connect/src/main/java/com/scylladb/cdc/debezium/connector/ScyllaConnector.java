package com.scylladb.cdc.debezium.connector;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.scylladb.cdc.cql.driver3.Driver3MasterCQL;
import com.scylladb.cdc.model.StreamId;
import com.scylladb.cdc.model.TableName;
import com.scylladb.cdc.model.TaskId;
import com.scylladb.cdc.model.master.Master;
import io.debezium.config.Configuration;
import io.debezium.util.Threads;
import org.apache.kafka.common.config.Config;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigValue;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ScyllaConnector extends SourceConnector {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Map<String, String> props;
    private Configuration config;

    private ScyllaMasterTransport masterTransport;
    private ExecutorService masterExecutor;
    private Master master;
    private Future<?> masterFuture;

    public ScyllaConnector() {
    }

    @Override
    public void start(Map<String, String> props) {
        this.props = props;

        final Configuration config = Configuration.from(props);
        final ScyllaConnectorConfig connectorConfig = new ScyllaConnectorConfig(config);
        this.config = config;

        // TODO - properly close the session
        List<InetSocketAddress> contactPoints = connectorConfig.getContactPoints();
        List<InetAddress> contactHosts = contactPoints.stream().map(InetSocketAddress::getAddress).collect(Collectors.toList());
        int contactPort = contactPoints.stream().map(InetSocketAddress::getPort).findFirst().get();

        Cluster cluster = Cluster.builder().addContactPoints(contactHosts).withPort(contactPort).build();
        Session session = cluster.connect();
        Driver3MasterCQL cql = new Driver3MasterCQL(session);
        this.masterTransport = new ScyllaMasterTransport(context(), new SourceInfo(connectorConfig));
        Set<TableName> tableNames = connectorConfig.getTableNames();
        this.master = new Master(masterTransport, cql, tableNames);

        this.masterExecutor = Threads.newSingleThreadExecutor(ScyllaConnector.class, connectorConfig.getLogicalName(), "scylla-lib-master-executor");
        this.masterFuture = this.masterExecutor.submit(() -> {
            try {
                master.run();
            } catch (ExecutionException e) {
                // TODO - handle exception
            }
        });
    }

    @Override
    public Class<? extends Task> taskClass() {
        return ScyllaConnectorTask.class;
    }

    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        Map<TaskId, SortedSet<StreamId>> tasks = masterTransport.getWorkerConfigurations();
        List<String> workerConfigs = new TaskConfigBuilder(tasks).buildTaskConfigs(maxTasks);
        return workerConfigs.stream().map(c -> config.edit()
                .with(ScyllaConnectorConfig.WORKER_CONFIG, c)
                .build().asMap()).collect(Collectors.toList());
    }

    @Override
    public void stop() {
        // TODO - properly close and stop all resources
        this.masterFuture.cancel(true);
    }

    @Override
    public Config validate(Map<String, String> connectorConfigs) {
        Configuration config = Configuration.from(connectorConfigs);
        Map<String, ConfigValue> results = config.validate(ScyllaConnectorConfig.EXPOSED_FIELDS);

        ConfigValue clusterIpAddressesConfig = results.get(ScyllaConnectorConfig.CLUSTER_IP_ADDRESSES.name());
        ConfigValue tableNamesConfig = results.get(ScyllaConnectorConfig.TABLE_NAMES.name());

        // Do a trial connection:
        if (clusterIpAddressesConfig.errorMessages().isEmpty()
                && tableNamesConfig.errorMessages().isEmpty()
                && results.get(ScyllaConnectorConfig.LOGICAL_NAME.name()).errorMessages().isEmpty()) {
            final ScyllaConnectorConfig connectorConfig = new ScyllaConnectorConfig(config);

            List<InetSocketAddress> contactPoints = connectorConfig.getContactPoints();
            Set<TableName> tableNames = connectorConfig.getTableNames();

            try (Cluster cluster = Cluster.builder().addContactPointsWithPorts(contactPoints).build();
                 Session session = cluster.connect()) {

                Metadata metadata = session.getCluster().getMetadata();
                for (TableName tableName : tableNames) {
                    KeyspaceMetadata keyspaceMetadata = metadata.getKeyspace(tableName.keyspace);
                    if (keyspaceMetadata == null) {
                        tableNamesConfig.addErrorMessage("Did not found table '" + tableName.keyspace + "."
                                + tableName.name + "' in Scylla cluster - missing keyspace '" + tableName.keyspace + "'.");
                        continue;
                    }

                    TableMetadata tableMetadata = keyspaceMetadata.getTable(tableName.name);
                    if (tableMetadata == null) {
                        tableNamesConfig.addErrorMessage("Did not found table '" + tableName.keyspace + "."
                                + tableName.name + "' in Scylla cluster.");
                        continue;
                    }

                    if (!tableMetadata.getOptions().isScyllaCDC()) {
                        tableNamesConfig.addErrorMessage("The table '" + tableName.keyspace + "."
                                + tableName.name + "' does not have CDC enabled.");
                    }
                }
            } catch (Exception ex) {
                clusterIpAddressesConfig.addErrorMessage("Unable to connect to Scylla cluster: " + ex.getMessage());
            }
        }

        return new Config(new ArrayList<>(results.values()));
    }

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public ConfigDef config() {
        return ScyllaConnectorConfig.configDef();
    }
}
