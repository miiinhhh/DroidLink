package com.example.droidlink.network;

import org.json.JSONException;
import org.json.JSONObject;

public class DiscoveryMessage {
    private static final String TYPE_DISCOVERY = "DROIDLINK_DISCOVERY";
    private static final String NAME = "DroidLink";
    private static final int PORT = 8080;

    public static String toJsonString() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_DISCOVERY);
            json.put("name", NAME);
            json.put("port", PORT);
            return json.toString();
        } catch (JSONException e) {
            return "{\"type\":\"DROIDLINK_DISCOVERY\",\"name\":\"DroidLink\",\"port\":8080}";
        }
    }
}
