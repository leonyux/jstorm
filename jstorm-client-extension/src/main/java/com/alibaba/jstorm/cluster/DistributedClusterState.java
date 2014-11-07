package com.alibaba.jstorm.cluster;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import backtype.storm.Config;

import com.alibaba.jstorm.callback.ClusterStateCallback;
import com.alibaba.jstorm.callback.WatcherCallBack;
import com.alibaba.jstorm.utils.PathUtils;
import com.alibaba.jstorm.zk.Zookeeper;
import com.netflix.curator.framework.CuratorFramework;

/**
 * All ZK interface implementation
 * 
 * @author yannian.mu
 * 
 */
public class DistributedClusterState implements ClusterState {

	private static Logger LOG = Logger.getLogger(DistributedClusterState.class);

	private Zookeeper zkobj = new Zookeeper();
	private CuratorFramework zk;
	private WatcherCallBack watcher;

	/**
	 * why run all callbacks, when receive one event
	 */
	private ConcurrentHashMap<UUID, ClusterStateCallback> callbacks = new ConcurrentHashMap<UUID, ClusterStateCallback>();

	private Map<Object, Object> conf;
	private AtomicBoolean active;

	public DistributedClusterState(Map<Object, Object> _conf) throws Exception {
		conf = _conf;

		// just mkdir STORM_ZOOKEEPER_ROOT dir
		// 利用CuratorFramework提供的接口创建storm zookeeper根节点
		// 使用DefaultWatcherCallBack作为默认的事件回调函数
		CuratorFramework _zk = mkZk();
		String path = String.valueOf(conf.get(Config.STORM_ZOOKEEPER_ROOT));
		zkobj.mkdirs(_zk, path);
		// 关闭了创建根节点使用的CuratorFramework
		_zk.close();

		active = new AtomicBoolean(true);

		// 构造WatcherCallBack，根据通知事件
		watcher = new WatcherCallBack() {
			@Override
			public void execute(KeeperState state, EventType type, String path) {
				if (active.get()) {
					if (!(state.equals(KeeperState.SyncConnected))) {
						LOG.warn("Received event " + state + ":" + type + ":"
								+ path + " with disconnected Zookeeper.");
					} else {
						LOG.info("Received event " + state + ":" + type + ":"
								+ path);
					}

					// 调用注册的每个回调函数
					if (!type.equals(EventType.None)) {
						for (Entry<UUID, ClusterStateCallback> e : callbacks
								.entrySet()) {
							ClusterStateCallback fn = e.getValue();
							fn.execute(type, path);
						}
					}
				}
			}
		};
		zk = null;
		// 构造CuratorFramework，并注册刚才定义的WatcherCallback
		zk = mkZk(watcher);

	}

	@SuppressWarnings("unchecked")
	private CuratorFramework mkZk() throws IOException {
		return zkobj.mkClient(conf,
				(List<String>) conf.get(Config.STORM_ZOOKEEPER_SERVERS),
				conf.get(Config.STORM_ZOOKEEPER_PORT), "");
	}

	@SuppressWarnings("unchecked")
	private CuratorFramework mkZk(WatcherCallBack watcher)
			throws NumberFormatException, IOException {
		return zkobj.mkClient(conf,
				(List<String>) conf.get(Config.STORM_ZOOKEEPER_SERVERS),
				conf.get(Config.STORM_ZOOKEEPER_PORT),
				String.valueOf(conf.get(Config.STORM_ZOOKEEPER_ROOT)), watcher);
	}

	@Override
	public void close() {
		this.active.set(false);
		zk.close();
	}

	@Override
	public void delete_node(String path) throws Exception {
		zkobj.deletereRcursive(zk, path);
	}

	@Override
	public List<String> get_children(String path, boolean watch)
			throws Exception {
		return zkobj.getChildren(zk, path, watch);
	}

	@Override
	public byte[] get_data(String path, boolean watch) throws Exception {
		return zkobj.getData(zk, path, watch);
	}

	@Override
	public void mkdirs(String path) throws Exception {
		zkobj.mkdirs(zk, path);

	}

	@Override
	public void set_data(String path, byte[] data) throws Exception {
		if (zkobj.exists(zk, path, false)) {
			zkobj.setData(zk, path, data);
		} else {
			zkobj.mkdirs(zk, PathUtils.parent_path(path));
			zkobj.createNode(zk, path, data, CreateMode.PERSISTENT);
		}

	}

	@Override
	public void set_ephemeral_node(String path, byte[] data) throws Exception {
		zkobj.mkdirs(zk, PathUtils.parent_path(path));
		if (zkobj.exists(zk, path, false)) {
			zkobj.setData(zk, path, data);
		} else {
			zkobj.createNode(zk, path, data, CreateMode.EPHEMERAL);
		}
	}

	@Override
	public UUID register(ClusterStateCallback callback) {
		UUID id = UUID.randomUUID();
		this.callbacks.put(id, callback);
		return id;
	}

	@Override
	public ClusterStateCallback unregister(UUID id) {
		return this.callbacks.remove(id);
	}

	@Override
	public boolean node_existed(String path, boolean watch) throws Exception {
		// TODO Auto-generated method stub
		return zkobj.existsNode(zk, path, watch);
	}

	@Override
	public void tryToBeLeader(String path, byte[] host) throws Exception {
		// TODO Auto-generated method stub
		zkobj.createNode(zk, path, host, CreateMode.EPHEMERAL);
	}
}
