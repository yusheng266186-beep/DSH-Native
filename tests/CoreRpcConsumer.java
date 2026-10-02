package dev.dsh.nativeapp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

/** Drives the production native transport against a real core. Secrets use stdin. */
public class CoreRpcConsumer {
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        Map<?, ?> connection = (Map<?, ?>) JsonValue.parse(input.readLine());
        CoreRpcClient client = new CoreRpcClient(((Number) connection.get("port")).intValue(), (String) connection.get("token"));
        try {
            CoreReadiness.awaitModelSettings(client, (String) connection.get("expectedSettings"), 30000);
            System.out.println("{\"ready\":true}");
            System.out.flush();
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    Map<?, ?> request = (Map<?, ?>) JsonValue.parse(line);
                    CoreRpcClient.Reply reply = client.call((String) request.get("method"), (String) request.get("args"));
                    if (Boolean.TRUE.equals(request.get("snapshot"))) {
                        Map<?, ?> response = (Map<?, ?>) JsonValue.parse(reply.body);
                        Map<?, ?> result = (Map<?, ?>) response.get("result");
                        String yaml = ModelSettingsSnapshot.fromDescriptionObject(result.get("value"));
                        if (Boolean.TRUE.equals(request.get("accountConfig"))) {
                            CoreRpcClient.Reply catalogReply = client.call("session/modelCatalog", "{}");
                            Map<?, ?> envelope = (Map<?, ?>) JsonValue.parse(catalogReply.body);
                            Map<?, ?> catalog = (Map<?, ?>) ((Map<?, ?>) envelope.get("result")).get("value");
                            List<LiveModelCatalog.Entry> entries = new ArrayList<LiveModelCatalog.Entry>();
                            for (Object group : (List<?>) catalog.get("groups")) {
                                Map<?, ?> provider = (Map<?, ?>) group;
                                if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(provider.get("id"))) continue;
                                for (Object item : (List<?>) provider.get("models")) {
                                    Map<?, ?> model = (Map<?, ?>) item;
                                    String id = (String) model.get("id"), details = "";
                                    boolean image = false, known = false;
                                    for (ModelConfig.Model existing : ModelConfig.modelsForProvider(yaml, ModelConfig.DEEPSEEK_ACCOUNT))
                                        if (existing.id.equals(id)) { details = existing.details; image = existing.image; known = existing.imageKnown; }
                                    entries.add(new LiveModelCatalog.Entry(id, (String) model.get("name"), true, image, true, known, details));
                                }
                            }
                            yaml = ModelCatalogSync.writeLiveCatalog(yaml, ModelConfig.DEEPSEEK_ACCOUNT, entries);
                            yaml = ModelConfig.updateSelection(yaml, new ModelConfig.Selection(ModelConfig.DEEPSEEK_ACCOUNT, entries.get(0).id, "max"));
                        }
                        System.out.println("{\"yaml\":\"" + Base64.getEncoder().encodeToString(yaml.getBytes("UTF-8")) + "\"}");
                    } else System.out.println(reply.body);
                } catch (Exception failure) { System.out.println("{\"error\":\"native core request failed\"}"); }
                System.out.flush();
            }
        } finally { client.close(); }
    }
}
