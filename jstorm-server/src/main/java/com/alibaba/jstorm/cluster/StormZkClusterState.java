package com.alibaba.jstorm.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Watcher.Event.EventType;

import backtype.storm.utils.Utils;

import com.alibaba.jstorm.callback.ClusterStateCallback;
import com.alibaba.jstorm.callback.RunnableCallback;
import com.alibaba.jstorm.daemon.supervisor.SupervisorInfo;
import com.alibaba.jstorm.task.Assignment;
import com.alibaba.jstorm.task.AssignmentBak;
import com.alibaba.jstorm.task.TaskInfo;
import com.alibaba.jstorm.task.error.TaskError;
import com.alibaba.jstorm.task.heartbeat.TaskHeartbeat;
import com.alibaba.jstorm.utils.JStormUtils;
import com.alibaba.jstorm.utils.PathUtils;
import com.alibaba.jstorm.utils.TimeUtils;
import com.alibaba.jstorm.zk.ZkConstant;
import com.alibaba.jstorm.zk.ZkTool;

public class StormZkClusterState implements StormClusterState {
	private static Logger LOG = Logger.getLogger(StormZkClusterState.class);

	private ClusterState cluster_state;

	private ConcurrentHashMap<String, RunnableCallback> assignment_info_callback;
	private AtomicReference<RunnableCallback> supervisors_callback;
	private AtomicReference<RunnableCallback> assignments_callback;
	private ConcurrentHashMap<String, RunnableCallback> storm_base_callback;
	private AtomicReference<RunnableCallback> master_callback;

	private UUID state_id;

	// TODO: what this solo mean?
	private boolean solo;

	public StormZkClusterState(Object cluster_state_spec) throws Exception {

		if (cluster_state_spec instanceof ClusterState) {
			solo = false;
			cluster_state = (ClusterState) cluster_state_spec;
		} else {

			solo = true;
			cluster_state = new DistributedClusterState(
					(Map) cluster_state_spec);
		}

		assignment_info_callback = new ConcurrentHashMap<String, RunnableCallback>();
		supervisors_callback = new AtomicReference<RunnableCallback>(null);
		assignments_callback = new AtomicReference<RunnableCallback>(null);
		storm_base_callback = new ConcurrentHashMap<String, RunnableCallback>();
		master_callback = new AtomicReference<RunnableCallback>(null);

		state_id = cluster_state.register(new ClusterStateCallback() {

			public <T> Object execute(T... args) {
				if (args == null) {
					LOG.warn("Input args is null");
					return null;
				} else if (args.length < 2) {
					LOG.warn("Input args is invalid, args length:"
							+ args.length);
					return null;
				}

				EventType zkEventTypes = (EventType) args[0];
				String path = (String) args[1];

				List<String> toks = PathUtils.tokenize_path(path);
				int size = toks.size();
				if (size >= 1) {
					String params = null;
					String root = toks.get(0);
					RunnableCallback fn = null;
					if (root.equals(Cluster.ASSIGNMENTS_ROOT)) {
						if (size == 1) {
							// set null and get the old value
							fn = assignments_callback.getAndSet(null);
						} else {
							params = toks.get(1);
							fn = assignment_info_callback.remove(params);
						}

					} else if (root.equals(Cluster.SUPERVISORS_ROOT)) {
						fn = supervisors_callback.getAndSet(null);
					} else if (root.equals(Cluster.STORMS_ROOT) && size > 1) {
						params = toks.get(1);
						fn = storm_base_callback.remove(params);
					} else if (root.equals(Cluster.MASTER_ROOT)) {
						fn = master_callback.getAndSet(null);
					} else {
						LOG.error("Unknown callback for subtree " + path);
					}

					if (fn != null) {
						// FIXME How to set the args
						// fn.setArgs(params, zkEventTypes, path);
						fn.run();
					}
				}

				return null;
			}

		});

		String[] pathlist = JStormUtils.mk_arr(Cluster.ASSIGNMENTS_SUBTREE,
				Cluster.TASKS_SUBTREE, Cluster.STORMS_SUBTREE,
				Cluster.SUPERVISORS_SUBTREE, Cluster.TASKBEATS_SUBTREE,
				Cluster.TASKERRORS_SUBTREE);
		for (String path : pathlist) {
			cluster_state.mkdirs(path);
		}

	}

	@Override
	public Assignment assignment_info(String topologyId,
			RunnableCallback callback) throws Exception {
		if (callback != null) {
			assignment_info_callback.put(topologyId, callback);
		}

		String assgnmentPath = Cluster.assignment_path(topologyId);

		byte[] znodeData = cluster_state.get_data(assgnmentPath,
				callback != null);

		Object data = Cluster.maybe_deserialize(znodeData);

		if (data == null) {
			return null;
		}
		return (Assignment) data;
	}

	@Override
	public List<String> assignments(RunnableCallback callback) throws Exception {
		if (callback != null) {
			assignments_callback.set(callback);
		}
		return cluster_state.get_children(Cluster.ASSIGNMENTS_SUBTREE,
				callback != null);
	}

	@Override
	public void set_assignment(String topologyId, Assignment info)
			throws Exception {
		cluster_state.set_data(Cluster.assignment_path(topologyId),
				Utils.serialize(info));
	}

	@Override
	public AssignmentBak assignment_bak(String topologyName) throws Exception {
		String assgnmentBakPath = ZkTool.assignment_bak_path(topologyName);

		byte[] znodeData = cluster_state.get_data(assgnmentBakPath, false);

		Object data = Cluster.maybe_deserialize(znodeData);

		if (data == null) {
			return null;
		}
		return (AssignmentBak) data;
	}

	@Override
	public void backup_assignment(String topologyName, AssignmentBak info)
			throws Exception {
		cluster_state.set_data(ZkTool.assignment_bak_path(topologyName),
				Utils.serialize(info));
	}

	@Override
	public void activate_storm(String topologyId, StormBase stormBase)
			throws Exception {
		String stormPath = Cluster.storm_path(topologyId);

		byte[] stormBaseData = Utils.serialize(stormBase);

		cluster_state.set_data(stormPath, stormBaseData);
	}

	@Override
	public List<String> active_storms() throws Exception {
		return cluster_state.get_children(Cluster.STORMS_SUBTREE, false);
	}

	@Override
	public List<String> heartbeat_storms() throws Exception {
		return cluster_state.get_children(Cluster.TASKBEATS_SUBTREE, false);
	}

	@Override
	public List<String> heartbeat_tasks(String topologyId) throws Exception {
		String taskbeatPath = Cluster.taskbeat_storm_root(topologyId);

		return cluster_state.get_children(taskbeatPath, false);
	}

	@Override
	public void remove_storm(String topologyId) throws Exception {
		cluster_state.delete_node(Cluster.assignment_path(topologyId));
		// wait 10 seconds, so supervisor will kill worker smoothly
		JStormUtils.sleepMs(10000);
		cluster_state.delete_node(Cluster.storm_task_root(topologyId));
		this.remove_storm_base(topologyId);
	}

	@Override
	public void remove_storm_base(String topologyId) throws Exception {
		cluster_state.delete_node(Cluster.storm_path(topologyId));
	}

	@Override
	public void remove_task_heartbeat(String topologyId, int taskId)
			throws Exception {
		String taskbeatPath = Cluster.taskbeat_path(topologyId, taskId);

		cluster_state.delete_node(taskbeatPath);
	}

	@Override
	public void report_task_error(String topologyId, int taskId, Throwable error)
			throws Exception {
		String path = Cluster.taskerror_path(topologyId, taskId);
		cluster_state.mkdirs(path);

		List<Integer> children = new ArrayList<Integer>();

		for (String str : cluster_state.get_children(path, false)) {
			children.add(Integer.parseInt(str));
		}

		Collections.sort(children);

		while (children.size() >= 10) {
			cluster_state.delete_node(path + Cluster.ZK_SEPERATOR
					+ children.remove(0));
		}

		String timestampPath = path + Cluster.ZK_SEPERATOR
				+ TimeUtils.current_time_secs();
		byte[] errorData = new String(JStormUtils.getErrorInfo(error))
				.getBytes();

		cluster_state.set_data(timestampPath, errorData);

	}

	@Override
	public void set_task(String topologyId, int taskId, TaskInfo info)
			throws Exception {
		String taskPath = Cluster.task_path(topologyId, taskId);

		byte[] taskData = Utils.serialize(info);

		cluster_state.set_data(taskPath, taskData);
	}

	@Override
	public void setup_heartbeats(String topologyId) throws Exception {
		String taskbeatPath = Cluster.taskbeat_storm_root(topologyId);

		cluster_state.mkdirs(taskbeatPath);
	}

	@Override
	public StormBase storm_base(String topologyId, RunnableCallback callback)
			throws Exception {
		if (callback != null) {
			storm_base_callback.put(topologyId, callback);
		}
		Object data = Cluster.maybe_deserialize(cluster_state.get_data(
				Cluster.storm_path(topologyId), callback != null));
		if (data == null) {
			return null;
		}
		return (StormBase) data;
	}

	@Override
	public void supervisor_heartbeat(String supervisorId, SupervisorInfo info)
			throws Exception {

		String supervisorPath = Cluster.supervisor_path(supervisorId);

		byte[] infoData = Utils.serialize(info);

		cluster_state.set_ephemeral_node(supervisorPath, infoData);
	}

	@Override
	public SupervisorInfo supervisor_info(String supervisorId) throws Exception {
		String supervisorPath = Cluster.supervisor_path(supervisorId);

		byte[] znodeData = cluster_state.get_data(supervisorPath, false);

		Object data = Cluster.maybe_deserialize(znodeData);
		if (data == null) {
			return null;
		}
		return (SupervisorInfo) data;

	}

	@Override
	public List<String> supervisors(RunnableCallback callback) throws Exception {
		if (callback != null) {
			supervisors_callback.set(callback);
		}
		return cluster_state.get_children(Cluster.SUPERVISORS_SUBTREE,
				callback != null);
	}

	@Override
	public List<String> task_error_storms() throws Exception {
		return cluster_state.get_children(Cluster.TASKERRORS_SUBTREE, false);
	}

	@Override
	public List<TaskError> task_errors(String topologyId, int taskId)
			throws Exception {
		String path = Cluster.taskerror_path(topologyId, taskId);
		cluster_state.mkdirs(path);

		List<String> children = cluster_state.get_children(path, false);
		List<TaskError> errors = new ArrayList<TaskError>();

		for (String str : children) {
			byte[] v = cluster_state.get_data(path + "/" + str, false);
			if (v != null) {
				TaskError error = new TaskError(new String(v),
						Integer.parseInt(str));
				errors.add(error);
			}
		}

		Collections.sort(errors, new Comparator<TaskError>() {

			@Override
			public int compare(TaskError o1, TaskError o2) {
				if (o1.getTimSecs() > o2.getTimSecs()) {
					return 1;
				}
				if (o1.getTimSecs() < o2.getTimSecs()) {
					return -1;
				}
				return 0;
			}
		});

		return errors;

	}

	@Override
	public TaskHeartbeat task_heartbeat(String topologyId, int taskId)
			throws Exception {
		String taskbeatPath = Cluster.taskbeat_path(topologyId, taskId);

		byte[] znodeData = cluster_state.get_data(taskbeatPath, false);

		Object data = Cluster.maybe_deserialize(znodeData);
		if (data == null) {
			return null;
		}
		return (TaskHeartbeat) data;
	}

	@Override
	public void task_heartbeat(String topologyId, int taskId, TaskHeartbeat info)
			throws Exception {
		String taskPath = Cluster.taskbeat_path(topologyId, taskId);

		byte[] taskData = Utils.serialize(info);

		cluster_state.set_data(taskPath, taskData);
	}

	@Override
	public List<Integer> task_ids(String stromId) throws Exception {

		String stormTaskPath = Cluster.storm_task_root(stromId);

		List<String> list = cluster_state.get_children(stormTaskPath, false);

		List<Integer> rtn = new ArrayList<Integer>();
		for (String str : list) {
			rtn.add(Integer.parseInt(str));
		}
		return rtn;
	}

	@Override
	public TaskInfo task_info(String topologyId, int taskId) throws Exception {

		String taskPath = Cluster.task_path(topologyId, taskId);

		byte[] znodeData = cluster_state.get_data(taskPath, false);

		Object data = Cluster.maybe_deserialize(znodeData);
		if (data == null) {
			return null;
		}
		return (TaskInfo) data;
	}

	@Override
	public List<String> task_storms() throws Exception {
		return cluster_state.get_children(Cluster.TASKS_SUBTREE, false);
	}

	@Override
	public void teardown_heartbeats(String topologyId) {
		try {
			String taskbeatPath = Cluster.taskbeat_storm_root(topologyId);

			cluster_state.delete_node(taskbeatPath);
		} catch (Exception e) {
			LOG.error("Could not teardown heartbeats for " + topologyId, e);
		}

	}

	@Override
	public void teardown_task_errors(String topologyId) {
		try {
			String taskerrPath = Cluster.taskerror_storm_root(topologyId);
			cluster_state.delete_node(taskerrPath);
		} catch (Exception e) {
			LOG.error("Could not teardown errors for " + topologyId, e);
		}
	}

	@Override
	public void update_storm(String topologyId, StormStatus newElems)
			throws Exception {
		/**
		 * FIXME, not sure where the old exist error or not The raw code
		 * (set-data cluster-state (storm-path storm-id) (-> (storm-base this
		 * storm-id nil) (merge new-elems) Utils/serialize)))
		 */

		StormBase base = this.storm_base(topologyId, null);

		if (base != null) {
			base.setStatus(newElems);
			cluster_state.set_data(Cluster.storm_path(topologyId),
					Utils.serialize(base));
		}

	}

	@Override
	public String get_leader_host() throws Exception {
		// TODO Auto-generated method stub
		return new String(cluster_state.get_data(Cluster.MASTER_SUBTREE, false));
	}

	@Override
	public boolean leader_existed() throws Exception {
		// TODO Auto-generated method stub
		return cluster_state.node_existed(Cluster.MASTER_SUBTREE, false);
	}

	@Override
	public void disconnect() {
		cluster_state.unregister(state_id);
		if (solo == true) {
			cluster_state.close();
		}
	}

	@Override
	public void register_nimbus_host(String host) throws Exception {
		// TODO Auto-generated method stub
		cluster_state.set_ephemeral_node(ZkConstant.NIMBUS_SLAVE_SUBTREE
				+ Cluster.ZK_SEPERATOR + host, null);
	}

	@Override
	public void unregister_nimbus_host(String host) throws Exception {
		cluster_state.delete_node(ZkConstant.NIMBUS_SLAVE_SUBTREE
				+ Cluster.ZK_SEPERATOR + host);
	}
	
	@Override
	public void update_follower_hb(String host, int time) throws Exception {
		cluster_state.set_data(ZkConstant.NIMBUS_SLAVE_SUBTREE
				+ Cluster.ZK_SEPERATOR + host,
				String.valueOf(time).getBytes("UTF-8"));
	}

	@Override
	public boolean try_to_be_leader(String path, String host,
			RunnableCallback callback) throws Exception {
		// TODO Auto-generated method stub
		if (callback != null)
			this.master_callback.set(callback);
		try {
			cluster_state.tryToBeLeader(path, host.getBytes());
		} catch (NodeExistsException e) {
			cluster_state.node_existed(path, true);
			LOG.info("leader is alive");
			return false;
		}
		return true;
	}

}
