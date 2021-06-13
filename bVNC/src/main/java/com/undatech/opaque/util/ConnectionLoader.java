package com.undatech.opaque.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.iiordanov.bVNC.App;
import com.iiordanov.bVNC.Constants;
import com.iiordanov.bVNC.Utils;
import com.iiordanov.util.PermissionsManager;
import com.trinity.android.apiclient.APICallback;
import com.trinity.android.apiclient.models.Action;
import com.trinity.android.apiclient.models.Node;
import com.trinity.android.apiclient.utils.ClientAPISettings;
import com.undatech.opaque.Connection;
import com.undatech.opaque.ConnectionSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import net.sqlcipher.database.SQLiteDatabase;
import com.iiordanov.bVNC.ConnectionBean;
import com.iiordanov.bVNC.Database;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;

public class ConnectionLoader {
    private static String TAG = "ConnectionLoader";
    private Context appContext;
    private boolean connectionsInSharedPrefs;
    private Map<String, Connection> connectionsById;
    private String[] connectionPreferenceFiles;
    private int numConnections = 0;
    private PermissionsManager permissionsManager;
    private Activity activity;
    private String cookie;

    public ConnectionLoader(Context appContext, Activity activity, boolean connectionsInSharedPrefs,
                            String cookie) {
        this.appContext = appContext;
        this.connectionsInSharedPrefs = connectionsInSharedPrefs;
        this.activity = activity;
        this.connectionsById = new HashMap<>();
        this.cookie = cookie;
        permissionsManager = new PermissionsManager();
        load();
    }

    public void load() {
        permissionsManager.requestPermissions(activity, false);
        if (cookie != null) {
            loadFromMorpheusly();
        }
        else if (connectionsInSharedPrefs) {
            loadFromSharedPrefs();
        } else {
            loadFromDatabase();
        }
    }

    private void loadFromDatabase() {
        Database database = new Database(this.appContext);
        SQLiteDatabase db = database.getWritableDatabase();

        ArrayList<ConnectionBean> connections = new ArrayList<ConnectionBean>();
        ConnectionBean.getAll(db, ConnectionBean.GEN_TABLE_NAME, connections, ConnectionBean.newInstance);
        Collections.sort(connections);
        numConnections = connections.size();
        if (connections.size() == 0) {
            android.util.Log.i(TAG, "No connections in the database");
        } else {
            for (int i = 0; i < connections.size(); i++) {
                Connection connection = connections.get(i);
                connection.setRuntimeId(Integer.toString(i));
                connectionsById.put(Integer.toString(i), connection);
            }
        }
        database.close();
    }

    private void loadFromSharedPrefs() {
        SharedPreferences sp = appContext.getSharedPreferences("generalSettings", Context.MODE_PRIVATE);
        String connections = sp.getString("connections", null);
        android.util.Log.d(TAG, "Loading connections from this list: " + connections);
        if (connections != null && !connections.equals("")) {
            connectionPreferenceFiles = connections.split(" ");
            numConnections = connectionPreferenceFiles.length;
            for (int i = 0; i < numConnections; i++) {
                Connection cs = new ConnectionSettings(connectionPreferenceFiles[i]);
                cs.setRuntimeId(Integer.toString(i));
                cs.load(appContext);
                android.util.Log.d(TAG, "Adding label: " + cs.getLabel());
                connectionsById.put(Integer.toString(i), cs);
            }
        }
    }

    private void loadFromMorpheusly() {
        android.util.Log.d(TAG, "running loadFromMorpheusly()");
        numConnections = 0;
        Map<String, Action> actions;
        long now = System.currentTimeMillis();

        // Retrieve actions from Morpheusly
        if (now - ClientAPISettings.getInstance(null).getActionsLastRetrieved() < ClientAPISettings.ACTIONS_RETRIEVE_PERIOD) {
            // Actual Load Actions Phase
            android.util.Log.d(TAG, "Action data is not old enough to retrieve: " +
                    Long.toString(now - ClientAPISettings.getInstance(null).getActionsLastRetrieved()));
            fillInActions(ClientAPISettings.getInstance(null).getActions());
        }
        else {
            // Retrieve from Morpheusly API Phase
            ClientAPISettings.getInstance(null).getApiClient().getActions().enqueue(new APICallback<Map<String, Action>>() {
                @Override
                public void onResponse(Call<Map<String, Action>> call, Response<Map<String, Action>> response) {
                    super.onResponse(call, response);
                    Map<String, Action> actions = response.body();
                    if (actions == null) {
                        actions = new HashMap<>();
                    }
                    for (String key : actions.keySet()) {
                        Action action = actions.get(key);
                        Action storedAction = ClientAPISettings.getInstance(null).getActions().get(key);
                        android.util.Log.d(TAG, "getActions action key is " + key);
                        android.util.Log.d(TAG, "getActions action from API is " + action.toJson());
                        if (storedAction != null) {
                            android.util.Log.d(TAG, "getActions action from stored action is " + storedAction.toJson());
                            android.util.Log.d(TAG, "getActions updating action: " + key);
                            storedAction.updateWithoutCredentials(action);
                            actions.put(key, storedAction);
                        }
                        else {
                            android.util.Log.d(TAG, "getActions not updating action: " + key);
                        }
                    }
                    android.util.Log.d(TAG, "Updating App actions and preferences");
                    ClientAPISettings.getInstance(null).setActions(actions);
                    SharedPreferences.Editor editor = ClientAPISettings.getInstance(null).getSharedPrefs().edit();
                    editor.putString("actions", ClientAPISettings.getInstance(null).getGson().toJson(actions, Map.class));
                    editor.apply();
                    android.util.Log.d(TAG, "Finished Updating App actions and preferences");
                    ClientAPISettings.getInstance(null).getApiClient().getNodes().enqueue(new APICallback<Map<String, Node>>() {
                        @Override
                        public void onResponse(Call<Map<String, Node>> call, Response<Map<String, Node>> response) {
                            android.util.Log.d(TAG, "getNodes response");
                            super.onResponse(call, response);
                            Map<String, Node> nodes = response.body();
                            //Remove itself from App Nodes, for now assume we don't want to carry out actions on ourself
                            String nodeId = ClientAPISettings.getInstance(null).getNodeId();
                            nodes.remove(nodeId);
                            ClientAPISettings.getInstance(null).setNodes(nodes);
                            SharedPreferences.Editor editor = ClientAPISettings.getInstance(null).getSharedPrefs().edit();
                            editor.putString("nodes", ClientAPISettings.getInstance(null).getGson().toJson(nodes, Map.class));
                            editor.apply();
                            android.util.Log.d(TAG, "Finished Updating App nodes and preferences");
                        }
                    });
                    ClientAPISettings.getInstance(null).setActionsLastRetrieved(System.currentTimeMillis());
                    Intent intent = new Intent(ClientAPISettings.REFRESH_DATA_RETRIEVED);
                    ClientAPISettings.getInstance(null).getBus().publish(
                            ClientAPISettings.REFRESH_DATA_RETRIEVED,
                            intent
                    );
                }
            });

        }
    }

    public void fillInActions(Map<String, Action> actions) {
        String tunnelledProtocol = "";
        if (Utils.isVnc(appContext.getPackageName())){
            tunnelledProtocol = "VNC";
        }
        else if (Utils.isRdp(appContext.getPackageName())){

            tunnelledProtocol = "RDP";
        }
        android.util.Log.d(TAG, "Tunneled Protocol is " + tunnelledProtocol);
        numConnections = actions.values().size();
        android.util.Log.d(TAG, "Retrieved " + numConnections + " actions");
        // Set actions on Grid
        int i = 0;
        for (Action a : actions.values()) {
            // Determine package that is running
            android.util.Log.d(TAG, "Action nickname: " + a.getToNodeName());
            android.util.Log.d(TAG, "Action interface name: " + a.getInterfaceName());
            if (!tunnelledProtocol.isEmpty() && tunnelledProtocol.equals(a.getTunnelledProtocol()) && ClientAPISettings.getInstance(null).getNodeId() != a.getToNodeName() )
            {
                android.util.Log.d(TAG, "Adding action");
                Connection connection = ConnectionBean.createForAction(appContext);
                connection.setNickname(a.getToNodeName() + " " + a.getInterfaceName());
                connection.setAction(a);
                connection.setRuntimeId(Integer.toString(i));
                connectionsById.put(Integer.toString(i), connection);
                i++;
                android.util.Log.d(TAG, "Added action");
            }
            else {
                android.util.Log.d(TAG, "Skipping action");
            }
        }

    }

    public boolean isConnectionsInSharedPrefs() {
        return connectionsInSharedPrefs;
    }

    public String[] getConnectionPreferenceFiles() {
        return connectionPreferenceFiles;
    }

    public int getNumConnections() {
        return numConnections;
    }

    public Map<String, Connection> getConnectionsById() {
        return connectionsById;
    }
}
