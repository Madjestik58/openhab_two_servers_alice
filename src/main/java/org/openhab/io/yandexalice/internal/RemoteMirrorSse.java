package org.openhab.io.yandexalice.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;

import org.json.JSONObject;

/** Minimal parser for the openHAB REST server-sent event stream. */
final class RemoteMirrorSse {
    record Change(String sourceName, String value, boolean command) {
    }

    static void read(Reader reader, Consumer<Change> receiver) throws IOException {
        BufferedReader lines = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(data, receiver);
                data.setLength(0);
            } else if (line.startsWith("data:")) {
                if (data.length() > 65536)
                    throw new IOException("Remote event is too large");
                if (!data.isEmpty())
                    data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        dispatch(data, receiver);
    }

    private static void dispatch(StringBuilder data, Consumer<Change> receiver) {
        if (data.isEmpty())
            return;
        try {
            JSONObject envelope = new JSONObject(data.toString());
            String type = envelope.optString("type");
            boolean command = "ItemCommandEvent".equals(type);
            if (!command && !"ItemStateEvent".equals(type))
                return;
            String topic = envelope.optString("topic");
            String suffix = command ? "/command" : "/state";
            String prefix = "openhab/items/";
            if (!topic.startsWith(prefix) || !topic.endsWith(suffix))
                return;
            String name = topic.substring(prefix.length(), topic.length() - suffix.length());
            if (name.isBlank() || name.contains("/"))
                return;
            JSONObject payload = new JSONObject(envelope.getString("payload"));
            String value = payload.optString("value");
            if (!value.isEmpty())
                receiver.accept(new Change(name, value, command));
        } catch (RuntimeException ignored) {
            // Ignore malformed or unrelated events without breaking the connection.
        }
    }
}
