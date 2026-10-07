package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型中心、服务商检测、项目覆盖和首次账号配置。 */
final class ModelCenterPanel {
    interface Host {
        NativeCoreApi coreApi();
        void openDeepSeekAccount(Runnable previous);
        File dshHome();
        String activeProject();
        void log(String message);
        void openCommandCodeUsage(String key);
        /** 让 DSH WebUI 重新读取刚写入的模型目录；任务运行时由宿主自行延后。 */
        void refreshModelCatalog();
        void closeModelCenter(boolean onboarding, boolean saved);
    }


    private ModelCenterPanel() { }

    /** Refresh only the selected provider and keep its result visible in the panel. */
    private static void refreshConfigured(final Activity activity, final Host host,
                                          final Dialog owner, final String provider,
                                          final String key, final Draft draft,
                                          final Button button, final TextView status,
                                          final Runnable refreshEfforts) {
        final NativeCoreApi api = host.coreApi();
        if (api == null) {
            status.setText(UiText.t("请等待 DSH 启动完成后重试", "Wait for DSH to finish starting, then retry."));
            status.setTextColor(DshUi.WARN());
            return;
        }
        if (!api.catalogGate.tryStart("model catalog")) {
            status.setText(UiText.t("模型目录正在更新，请等待本次请求完成", "A catalog refresh is already running. Wait for it to finish."));
            status.setTextColor(DshUi.WARN());
            return;
        }
        final int revision = draft.checkRevision;
        DshUi.setBusy(button, UiText.t("更新模型列表", "Refresh models"),
                UiText.t("正在读取…", "Loading…"), true);
        status.setText(UiText.t("正在读取 ", "Loading ")
                + ModelConfig.providerName(provider, UiText.isEnglish())
                + UiText.t(" 的模型列表…", " model catalog…"));
        status.setTextColor(DshUi.TEXT_2());
        host.log("开始读取上游模型目录: " + provider);
        api.execute(new NativeCoreApi.Work() {
            @Override public Object run() throws Exception {
                final File home = host.dshHome();
                return ModelCatalogRefresh.run(provider, key, new ModelCatalogRefresh.Backend() {
                    @Override public void checkOpen() throws java.io.IOException { api.checkOpen(); }
                    @Override public String credential(String selected) throws Exception {
                        return ModelConfig.readCredentialRef(ProjectModelSettings.readFile(
                                new File(home, ".credentials.yaml")), ModelConfig.credentialKey(selected));
                    }
                    @Override public ProviderCheck.Result fetch(String selected, String savedKey) throws Exception {
                        ProviderCheck.Result result = fetchCatalog(api, selected, savedKey);
                        host.log("上游模型目录响应: " + selected + " HTTP " + result.httpCode
                                + "，模型 " + result.models.size() + " 个");
                        if (result.state == ProviderCheck.READY) activity.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (owner.isShowing()) status.setText(UiText.t("模型列表已读取，正在保存…",
                                        "Model catalog fetched; saving…"));
                            }
                        });
                        return result;
                    }
                    @Override public String settings() throws Exception { return api.modelSettings(home); }
                    @Override public void write(String settings) throws Exception {
                        ProjectModelSettings.writeFileAtomic(new File(home, "settings.yaml"), settings);
                    }
                });
            }
        }, new NativeCoreApi.Callback() {
            @Override public void complete(Object value, Throwable failure) {
                api.catalogGate.finish("model catalog");
                if (owner.isShowing()) DshUi.setBusy(button,
                        UiText.t("更新模型列表", "Refresh models"), UiText.t("正在读取…", "Loading…"), false);
                if (failure != null) {
                    host.log("模型目录更新失败: " + provider + " / " + failure.getClass().getSimpleName());
                    if (owner.isShowing()) {
                        status.setText(catalogExceptionMessage(failure));
                        status.setTextColor(DshUi.ERROR());
                    }
                    return;
                }
                ModelCatalogRefresh.Result result = (ModelCatalogRefresh.Result) value;
                if (!result.saved()) {
                    host.log("上游模型目录读取失败: " + provider + " HTTP " + result.upstream.httpCode);
                    if (owner.isShowing()) renderCatalogFailure(status, result.upstream);
                    return;
                }
                host.log("已同步上游模型到 DSH: " + provider + "，" + result.entries.size() + " 个");
                if (owner.isShowing()) {
                    if (revision == draft.checkRevision && provider.equals(draft.provider)) {
                        draft.putCatalog(provider, result.entries);
                        if (refreshEfforts != null) refreshEfforts.run();
                    }
                    status.setText(ModelConfig.providerName(provider, UiText.isEnglish())
                            + UiText.t("：已读取并保存 ", ": fetched and saved ")
                            + result.entries.size() + UiText.t(" 个模型。聊天列表将在任务空闲时更新。",
                                    " models. The chat catalog will apply when DSH is idle.")
                            + (revision == draft.checkRevision ? "" : UiText.t(
                                    "当前输入已改变，请重新刷新后保存。",
                                    "Your inputs changed. Refresh again before saving.")));
                    status.setTextColor(DshUi.SUCCESS());
                }
                host.refreshModelCatalog();
            }
        });
    }

    private static String catalogExceptionMessage(Throwable failure) {
        if (failure instanceof java.net.SocketTimeoutException) return UiText.t(
                "读取模型列表超时，请检查网络后重试。原有模型列表已保留。",
                "The model request timed out. Check your connection and retry. Your existing catalog is preserved.");
        if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.ConnectException)
            return UiText.t("无法连接模型服务，请检查网络或代理后重试。原有模型列表已保留。",
                    "Could not reach the model provider. Check your connection or proxy and retry. Your existing catalog is preserved.");
        return UiText.t("更新模型列表失败（", "Model refresh failed (")
                + (failure == null ? "unknown" : failure.getClass().getSimpleName())
                + UiText.t("）。请重试；原有模型列表已保留。",
                        "). Please retry; your existing catalog is preserved.");
    }

    static void show(Activity activity, Host host, boolean onboarding) {
        show(activity, host, host.activeProject(), onboarding, false);
    }

    static void showProject(Activity activity, Host host, String project) {
        show(activity, host, project, false, true);
    }

    private static void show(final Activity act, final Host host, final String project,
                             final boolean onboarding, final boolean projectOnly) {
        final NativeCoreApi api = host.coreApi();
        if (api == null) {
            DshUi.toast(act, UiText.t("请等待 DSH 启动完成", "Wait for DSH to finish starting."));
            host.closeModelCenter(onboarding, false);
            return;
        }
        api.execute(new NativeCoreApi.Work() {
            @Override public Object run() throws Exception { return api.modelSettings(host.dshHome()); }
        }, new NativeCoreApi.Callback() {
            @Override public void complete(Object value, Throwable failure) {
                if (failure != null) {
                    host.log("读取模型配置失败: " + failure.getClass().getSimpleName());
                    DshUi.toast(act, UiText.t("读取模型配置失败，请重试", "Could not read model settings. Please retry."));
                    host.closeModelCenter(onboarding, false);
                } else showLoaded(act, host, project, onboarding, projectOnly, (String) value);
            }
        });
    }

    private static void showLoaded(final Activity act, final Host host, final String project,
                                   final boolean onboarding, final boolean projectOnly, final String settingsText) {
        try {
            final File home = host.dshHome();
            final File settingsFile = new File(home, "settings.yaml");
            final File credentialsFile = new File(home, ".credentials.yaml");
            final File projectsFile = new File(home, ProjectModelSettings.FILE_NAME);
            final String credentialsText = ProjectModelSettings.readFile(credentialsFile);
            final ProjectModelSettings.State projectState =
                    ProjectModelSettings.parse(ProjectModelSettings.readFile(projectsFile));
            final ModelConfig.Selection fileSelection = ModelConfig.readSelection(settingsText);
            if (projectState.global == null || !projectState.global.valid()) {
                projectState.global = fileSelection;
            }

            final Draft draft = new Draft();
            draft.globalScope = !projectOnly && !ProjectModelSettings.hasOverride(projectState, project);
            ModelConfig.Selection initial = draft.globalScope
                    ? projectState.global
                    : ProjectModelSettings.effective(projectState, project, fileSelection);
            draft.apply(validOrDefault(initial));

            LinearLayout body = DshUi.paddedBody(act);
            body.addView(DshUi.title(act, onboarding
                    ? UiText.t("连接模型服务", "Connect a model provider")
                    : UiText.t("模型中心", "Model center")));
            body.addView(DshUi.hint(act, onboarding
                    ? UiText.t("选择服务商，登录账号或填写 API Key，并确认默认模型。检测只读取模型列表，不会产生模型调用费用。",
                            "Choose a provider, sign in or add an API key, and confirm a default model. The check only reads the model list and does not make a billed model call.")
                    : UiText.t("统一管理服务商、模型和思考强度。模型变更用于新会话，已有会话保留自己的模型。",
                            "Manage providers, models, and reasoning effort. Changes apply to new sessions; existing sessions keep their recorded model.")),
                    DshUi.fullWidth(act, 7));

            final Button global = DshUi.toggleButton(act,
                    UiText.t("全局默认", "Global default"), draft.globalScope);
            final Button perProject = DshUi.toggleButton(act,
                    WorkspaceProjects.displayName(project), !draft.globalScope);
            final Button followGlobal = DshUi.button(act,
                    UiText.t("当前项目改为跟随全局", "Use global default for this project"), false);
            if (!onboarding) {
                body.addView(DshUi.sectionLabel(act,
                        UiText.t("配置范围", "Configuration scope")), DshUi.fullWidth(act, 16));
                LinearLayout scopeRow = row(act);
                addEqual(scopeRow, global, 0);
                addEqual(scopeRow, perProject, 6);
                body.addView(scopeRow, DshUi.fullWidth(act, 6));
                body.addView(followGlobal, DshUi.fullWidth(act, 6));
                if (projectOnly) global.setEnabled(false);
            }

            body.addView(DshUi.sectionLabel(act,
                    UiText.t("服务商", "Provider")), DshUi.fullWidth(act, 20));
            final Button cc = DshUi.toggleButton(act, "Command Code",
                    ModelConfig.COMMAND_CODE.equals(draft.provider));
            final Button ds = DshUi.toggleButton(act,
                    UiText.t("DeepSeek API Key", "DeepSeek API key"),
                    ModelConfig.DEEPSEEK.equals(draft.provider));
            final Button dsAccount = DshUi.toggleButton(act,
                    UiText.t("DeepSeek 账号", "DeepSeek account"),
                    ModelConfig.DEEPSEEK_ACCOUNT.equals(draft.provider));
            body.addView(cc, DshUi.fullWidth(act, 6));
            body.addView(ds, DshUi.fullWidth(act, 6));
            body.addView(dsAccount, DshUi.fullWidth(act, 6));
            Button accountSettings = DshUi.button(act,
                    UiText.t("DeepSeek 账号与余额", "DeepSeek account & balances"), false);
            body.addView(accountSettings, DshUi.fullWidth(act, 6));

            final TextView ccLabel = DshUi.label(act, "Command Code API Key");
            body.addView(ccLabel, DshUi.fullWidth(act, 14));
            final EditText ccKey = DshUi.input(act, ModelConfig.readCredentialRef(
                    credentialsText, "COMMANDCODE_API_KEY"), true);
            body.addView(ccKey, DshUi.fullWidth(act, 6));
            final TextView dsLabel = DshUi.label(act, "DeepSeek API Key");
            body.addView(dsLabel, DshUi.fullWidth(act, 14));
            final EditText dsKey = DshUi.input(act, ModelConfig.readCredentialRef(
                    credentialsText, "DEEPSEEK_API_KEY"), true);
            body.addView(dsKey, DshUi.fullWidth(act, 6));

            body.addView(DshUi.sectionLabel(act,
                    UiText.t("模型", "Model")), DshUi.fullWidth(act, 20));
            final EditText model = DshUi.input(act, draft.model, false);
            model.setSingleLine(true);
            model.setFocusable(false);
            model.setClickable(true);
            model.setHint(UiText.t("从服务商实时列表选择", "Choose from the provider's live catalog"));
            body.addView(model, DshUi.fullWidth(act, 6));
            final Button choose = DshUi.button(act,
                    UiText.t("选择模型", "Choose model"), false);
            body.addView(choose, DshUi.fullWidth(act, 6));
            final Button updateCatalog = DshUi.button(act,
                    UiText.t("更新模型列表", "Refresh models"), false);
            body.addView(updateCatalog, DshUi.fullWidth(act, 6));
            final TextView catalogStatus = DshUi.status(act,
                    UiText.t("模型列表尚未刷新", "The model catalog has not been refreshed."));
            catalogStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(catalogStatus, DshUi.fullWidth(act, 6));
            body.addView(DshUi.hint(act, UiText.t(
                    "更新会直接同步已配置服务商的目录；确认空闲后自动应用。选择新的默认模型仍需保存。",
                    "Updating directly syncs saved provider catalogs and applies them when idle. Save separately to change the default model.")),
                    DshUi.fullWidth(act, 8));

            body.addView(DshUi.sectionLabel(act,
                    UiText.t("思考强度", "Reasoning effort")), DshUi.fullWidth(act, 20));
            final LinearLayout effortArea = new LinearLayout(act);
            effortArea.setOrientation(LinearLayout.VERTICAL);
            body.addView(effortArea, DshUi.fullWidth(act, 6));
            final TextView effortHint = DshUi.hint(act, "");
            body.addView(effortHint, DshUi.fullWidth(act, 5));
            final Runnable refreshEfforts = new Runnable() {
                @Override public void run() {
                    renderEfforts(act, effortArea, effortHint, draft, settingsText);
                }
            };

            final TextView checkStatus = DshUi.status(act,
                    UiText.t("尚未检测服务商", "Provider has not been checked"));
            body.addView(checkStatus, DshUi.fullWidth(act, 14));
            final Button check = DshUi.button(act,
                    UiText.t("检测连接与模型", "Check connection and model"), false);
            body.addView(check, DshUi.fullWidth(act, 6));

            if (!onboarding) {
                Button usage = DshUi.button(act,
                        UiText.t("Command Code 用量", "Command Code usage"), false);
                usage.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        host.openCommandCodeUsage(ccKey.getText().toString().trim());
                    }
                });
                body.addView(usage, DshUi.fullWidth(act, 16));
            }

            final Button back = DshUi.button(act, onboarding
                    ? UiText.t("稍后设置", "Set up later")
                    : UiText.t("返回工具与设置", "Back to tools & settings"), false);
            final Button save = DshUi.button(act, onboarding
                    ? UiText.t("保存并完成", "Save and finish") : UiText.t("保存", "Save"), true);
            final Dialog dialog = DshUi.dialog(act, DshUi.scroll(act, body),
                    DshUi.footer(act, back, save), 760);

            final Runnable invalidateCheck = new Runnable() {
                @Override public void run() {
                    draft.checkRevision++;
                    DshUi.setBusy(check,
                            UiText.t("检测连接与模型", "Check connection and model"),
                            UiText.t("正在检测…", "Checking…"), false);
                    checkStatus.setText(UiText.t("尚未检测服务商",
                            "Provider has not been checked"));
                    checkStatus.setTextColor(DshUi.TEXT_2());
                }
            };
            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    invalidateCheck.run();
                    DshUi.setToggleState(cc, ModelConfig.COMMAND_CODE.equals(draft.provider));
                    DshUi.setToggleState(ds, ModelConfig.DEEPSEEK.equals(draft.provider));
                    DshUi.setToggleState(dsAccount, ModelConfig.DEEPSEEK_ACCOUNT.equals(draft.provider));
                    ccLabel.setVisibility(ModelConfig.COMMAND_CODE.equals(draft.provider) ? View.VISIBLE : View.GONE);
                    ccKey.setVisibility(ccLabel.getVisibility());
                    dsLabel.setVisibility(ModelConfig.DEEPSEEK.equals(draft.provider) ? View.VISIBLE : View.GONE);
                    dsKey.setVisibility(dsLabel.getVisibility());
                    DshUi.setToggleState(global, draft.globalScope);
                    DshUi.setToggleState(perProject, !draft.globalScope);
                    followGlobal.setVisibility(!onboarding && !draft.globalScope
                            && !draft.followGlobal
                            && ProjectModelSettings.hasOverride(projectState, project)
                            ? View.VISIBLE : View.GONE);
                    model.setText(draft.model);
                    model.setSelection(model.getText().length());
                    refreshEfforts.run();
                }
            };

            ccKey.addTextChangedListener(keyWatcher(draft, ModelConfig.COMMAND_CODE,
                    invalidateCheck));
            dsKey.addTextChangedListener(keyWatcher(draft, ModelConfig.DEEPSEEK,
                    invalidateCheck));

            cc.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    selectProvider(draft, ModelConfig.COMMAND_CODE);
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            ds.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    selectProvider(draft, ModelConfig.DEEPSEEK);
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            dsAccount.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    selectProvider(draft, ModelConfig.DEEPSEEK_ACCOUNT);
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            accountSettings.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() {
                            host.openDeepSeekAccount(new Runnable() {
                                @Override public void run() { show(act, host, project, onboarding, projectOnly); }
                            });
                        }
                    });
                }
            });
            global.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    if (projectOnly) return;
                    draft.globalScope = true;
                    draft.apply(validOrDefault(projectState.global));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            perProject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    draft.globalScope = false;
                    draft.apply(validOrDefault(
                            ProjectModelSettings.effective(projectState, project, fileSelection)));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            followGlobal.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = true;
                    draft.apply(validOrDefault(projectState.global));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            final View.OnClickListener openChooser = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
                    if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(draft.provider)
                            && selectedKey(draft.provider, ccKey, dsKey).length() == 0) {
                        catalogStatus.setText(UiText.t("请先填写当前服务商的 API Key",
                                "Add the API key for the selected provider first."));
                        catalogStatus.setTextColor(DshUi.WARN());
                        return;
                    }
                    showModelChooser(act, host, dialog, settingsText, draft, model,
                            selectedKey(draft.provider, ccKey, dsKey), refresh);
                }
            };
            choose.setOnClickListener(openChooser);
            updateCatalog.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        String savedKey = ModelConfig.readCredentialRef(
                                ProjectModelSettings.readFile(credentialsFile), ModelConfig.credentialKey(draft.provider));
                        if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(draft.provider)
                                && (savedKey.length() == 0 || !savedKey.equals(selectedKey(draft.provider, ccKey, dsKey)))) {
                            catalogStatus.setText(selectedKey(draft.provider, ccKey, dsKey).length() == 0
                                    ? UiText.t("请先填写当前服务商的 API Key", "Add the API key for the selected provider first.")
                                    : UiText.t("当前密钥尚未保存。请在实时列表选择模型，再点击保存。",
                                            "This key has not been saved. Choose a live model, then select Save."));
                            catalogStatus.setTextColor(DshUi.WARN());
                            openChooser.onClick(v);
                        } else refreshConfigured(act, host, dialog, draft.provider,
                                selectedKey(draft.provider, ccKey, dsKey), draft,
                                updateCatalog, catalogStatus, refreshEfforts);
                    } catch (Throwable error) {
                        host.log("读取已保存的账号失败: " + error.getClass().getSimpleName());
                        catalogStatus.setText(UiText.t("读取已保存的账号失败，请重试", "Could not read the saved account. Please retry."));
                        catalogStatus.setTextColor(DshUi.ERROR());
                    }
                }
            });
            model.setOnClickListener(openChooser);
            check.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
                    String key = ModelConfig.DEEPSEEK.equals(draft.provider)
                            ? dsKey.getText().toString().trim()
                            : ccKey.getText().toString().trim();
                    runCheck(act, host, dialog, settingsText, draft, draft.checkRevision,
                            draft.provider, key, draft.model, checkStatus, check, refreshEfforts);
                }
            });
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
                    refreshEfforts.run();
                    final ModelConfig.Selection selection = new ModelConfig.Selection(
                            draft.provider, draft.model, draft.effort);
                    if (draft.followGlobal && projectState.global != null
                            && !selection.equals(projectState.global)) {
                        draft.followGlobal = false;
                    }
                    if (!selection.valid()) {
                        DshUi.toast(act, UiText.t("请选择有效的服务商和模型",
                                "Choose a valid provider and model"));
                        return;
                    }
                    if (!LiveModelCatalog.canSave(draft.baseline, selection, onboarding,
                            draft.catalogLoaded(selection.provider),
                            draft.catalog(selection.provider))) {
                        DshUi.toast(act, UiText.t(
                                "请先刷新上游模型列表，并选择上游返回的模型",
                                "Refresh the upstream catalog and choose a model returned by the provider."));
                        return;
                    }
                    String selectedKey = ModelConfig.DEEPSEEK.equals(selection.provider)
                            ? dsKey.getText().toString().trim()
                            : ccKey.getText().toString().trim();
                    if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(selection.provider) && selectedKey.length() == 0) {
                        DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                                "Add the API key for the selected provider"));
                        return;
                    }
                    final String saveIdle = onboarding
                            ? UiText.t("保存并完成", "Save and finish")
                            : UiText.t("保存", "Save");
                    DshUi.setBusy(save, saveIdle,
                            UiText.t("保存中", "Saving"), true);
                    final String commandKey = ccKey.getText().toString().trim();
                    final String deepSeekKey = dsKey.getText().toString().trim();
                    final boolean globalScope = draft.globalScope;
                    final boolean followsGlobal = draft.followGlobal;
                    final boolean catalogLoaded = draft.catalogLoaded(selection.provider);
                    final List<LiveModelCatalog.Entry> liveEntries =
                            new ArrayList<LiveModelCatalog.Entry>(draft.catalog(selection.provider));
                    final NativeCoreApi api = host.coreApi();
                    if (api == null) {
                        DshUi.setBusy(save, saveIdle, UiText.t("保存中", "Saving"), false);
                        DshUi.toast(act, UiText.t("请等待 DSH 启动完成", "Wait for DSH to finish starting."));
                        return;
                    }
                    api.execute(new NativeCoreApi.Work() {
                        @Override public Object run() throws Exception {
                            String latestSettings = api.modelSettings(home);
                            ProjectModelSettings.State latestState = ProjectModelSettings.parse(
                                    ProjectModelSettings.readFile(projectsFile));
                            ModelConfig.Selection latestFileSelection =
                                    ModelConfig.readSelection(latestSettings);
                            if (latestState.global == null || !latestState.global.valid()) {
                                ProjectModelSettings.setGlobal(latestState,
                                        projectState.global != null && projectState.global.valid()
                                                ? projectState.global : latestFileSelection);
                            }
                            if (globalScope || onboarding) {
                                ProjectModelSettings.setGlobal(latestState, selection);
                            } else if (followsGlobal) {
                                ProjectModelSettings.clearOverride(latestState, project);
                            } else {
                                ProjectModelSettings.setOverride(latestState, project, selection);
                            }
                            ModelConfig.Selection effective = ProjectModelSettings.effective(
                                    latestState, project, latestFileSelection);
                            String nextSettings = ModelConfig.updateSelection(latestSettings, effective);
                            if (catalogLoaded) {
                                nextSettings = ModelCatalogSync.writeLiveCatalog(nextSettings,
                                        selection.provider, liveEntries);
                            }
                            saveKey(api, credentialsText, "COMMANDCODE_API_KEY", commandKey);
                            saveKey(api, credentialsText, "DEEPSEEK_API_KEY", deepSeekKey);
                            api.checkOpen();
                            ProjectModelSettings.writeFileAtomic(projectsFile,
                                    ProjectModelSettings.serialize(latestState));
                            ProjectModelSettings.writeFileAtomic(settingsFile, nextSettings);
                            return null;
                        }
                    }, new NativeCoreApi.Callback() {
                        @Override public void complete(Object value, Throwable error) {
                            if (error == null) {
                                host.refreshModelCatalog();
                                host.log("模型配置已保存: " + selection.provider + " / "
                                        + selection.model + " / " + selection.effort
                                        + (globalScope || onboarding ? "（全局）"
                                        : followsGlobal ? "（跟随全局）" : "（项目覆盖）"));
                                if (!dialog.isShowing()) return;
                                dialog.dismiss();
                                host.closeModelCenter(onboarding, true);
                            } else {
                                host.log("模型配置保存失败: " + error.getClass().getSimpleName());
                                if (!dialog.isShowing()) return;
                                DshUi.setBusy(save, saveIdle, UiText.t("保存中", "Saving"), false);
                                DshUi.toast(act, UiText.t("保存失败：", "Save failed: ")
                                        + safeMessage(error));
                            }
                        }
                    });
                }
            });

            final Runnable returnToParent = new Runnable() {
                @Override public void run() {
                    host.closeModelCenter(onboarding, false);
                }
            };
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    DshUi.swapDialog(dialog, true, returnToParent);
                }
            });
            DshUi.onBack(dialog, returnToParent);
            refresh.run();
            dialog.show();
        } catch (Throwable error) {
            host.log("打开模型中心失败: " + error);
            DshUi.toast(act, UiText.t("打开模型中心失败", "Could not open model center"));
            host.closeModelCenter(onboarding, false);
        }
    }

    private static void showModelChooser(final Activity act, final Host host,
                                         final Dialog owner, final String settings,
                                         final Draft draft, final EditText target,
                                         final String key, final Runnable onSelection) {
        if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(draft.provider) && key.length() == 0) {
            DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                    "Add the API key for the selected provider"));
            return;
        }
        final String provider = draft.provider;
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, UiText.t("选择模型", "Choose model")));
        body.addView(DshUi.hint(act, ModelConfig.DEEPSEEK_ACCOUNT.equals(provider)
                ? UiText.t("读取账号模型目录。实际使用取决于 DeepSeek 账号权限和额度；图片和思考标签为能力提示。",
                        "Read the account model catalog. Usage depends on DeepSeek account permissions and balance; image and reasoning labels are capability hints.")
                : UiText.t(
                "列表直接从服务商网络接口读取，不使用 App 内置列表。上游返回的模型都可以直接选择；"
                        + "图片和思考标签仅作为当前运行环境的能力提示。",
                "The list is fetched directly from the provider. Every model returned upstream can be selected; "
                        + "image and reasoning labels are only capability hints from this runtime.")),
                DshUi.fullWidth(act, 6));
        final TextView status = DshUi.status(act,
                UiText.t("准备读取上游模型列表…", "Preparing to load the upstream catalog…"));
        body.addView(status, DshUi.fullWidth(act, 10));
        final EditText search = DshUi.input(act, "", false);
        search.setSingleLine(true);
        search.setHint(UiText.t("搜索上游模型 ID", "Search upstream model IDs"));
        body.addView(search, DshUi.fullWidth(act, 6));
        final TextView count = DshUi.hint(act, "");
        body.addView(count, DshUi.fullWidth(act, 5));
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, DshUi.fullWidth(act, 5));
        Button back = DshUi.button(act, UiText.t("返回模型中心", "Back to model center"), false);
        final Button reload = DshUi.button(act,
                UiText.t("刷新上游列表", "Refresh upstream catalog"), true);
        final Dialog dialog = DshUi.dialogFill(act, DshUi.scroll(act, body),
                DshUi.footer(act, back, reload), 720);
        final CatalogDialogState state = new CatalogDialogState();
        final Runnable fill = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                int matched = 0;
                int shown = 0;
                for (final LiveModelCatalog.Entry item : state.entries) {
                    String haystack = (item.id + " " + item.name).toLowerCase(java.util.Locale.ROOT);
                    if (query.length() > 0 && !haystack.contains(query)) continue;
                    matched++;
                    if (shown >= 80) continue;
                    String suffix = (item.image ? UiText.t(" · 图片", " · vision")
                            : item.imageKnown ? UiText.t(" · 仅文字", " · text only")
                            : UiText.t(" · 视觉能力未知", " · vision unknown"))
                            + UiText.t(" · 强度：", " · Effort: ")
                            + (ModelReasoning.choices(draft.provider, item.id, item.details).isEmpty()
                                ? UiText.t("服务商默认", "provider default")
                                : ModelReasoning.choices(draft.provider, item.id, item.details).toString())
                            + UiText.t(" · 上游可用", " · upstream");
                    String label = item.name.equals(item.id)
                            ? item.id + suffix : item.name + "\n" + item.id + suffix;
                    Button button = DshUi.button(act, label,
                            item.selectable && item.id.equals(draft.model));
                    button.setAllCaps(false);
                    button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                    button.setEnabled(true);
                    button.setAlpha(1f);
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            draft.followGlobal = false;
                            draft.model = item.id;
                            draft.rememberChoice();
                            target.setText(item.id);
                            target.setSelection(target.getText().length());
                            if (onSelection != null) onSelection.run();
                            DshUi.choiceActivated(v);
                            dialog.dismiss();
                        }
                    });
                    list.addView(button, DshUi.fullWidth(act, 5));
                    shown++;
                }
                count.setText(UiText.t("匹配 " + matched + " 个上游模型"
                                + (matched > shown ? "，显示前 " + shown + " 个" : ""),
                        matched + " upstream models"
                                + (matched > shown ? ", showing first " + shown : "")));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { fill.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        final Runnable load = new Runnable() {
            @Override public void run() {
                final int generation = ++state.generation;
                DshUi.setBusy(reload,
                        UiText.t("刷新上游列表", "Refresh upstream catalog"),
                        UiText.t("正在读取…", "Loading…"), true);
                status.setText(UiText.t("正在从服务商读取模型列表…",
                        "Loading models from the provider…"));
                status.setTextColor(DshUi.TEXT_2());
                requestCatalog(act, host.coreApi(), provider, key, new CatalogCallback() {
                    @Override public void complete(ProviderCheck.Result result, Throwable error) {
                        if (act.isFinishing() || act.isDestroyed() || !dialog.isShowing()
                                || !owner.isShowing() || state.generation != generation) return;
                        DshUi.setBusy(reload,
                                UiText.t("刷新上游列表", "Refresh upstream catalog"),
                                UiText.t("正在读取…", "Loading…"), false);
                        if (error != null) {
                            state.entries = new ArrayList<LiveModelCatalog.Entry>();
                            status.setText(UiText.t("读取失败：", "Load failed: ")
                                    + safeMessage(error));
                            status.setTextColor(DshUi.ERROR());
                            fill.run();
                            host.log("上游模型目录读取失败: "
                                    + error.getClass().getSimpleName());
                            return;
                        }
                        if (result.state != ProviderCheck.READY) {
                            state.entries = new ArrayList<LiveModelCatalog.Entry>();
                            if (ModelConfig.DEEPSEEK_ACCOUNT.equals(provider) && result.state == ProviderCheck.KEY_REJECTED) {
                                status.setText(UiText.t("请先在模型中心登录 DeepSeek 账号", "Sign in to DeepSeek in Model center first."));
                                status.setTextColor(DshUi.WARN());
                            } else renderCatalogFailure(status, result);
                            fill.run();
                            host.log("上游模型目录读取失败: " + provider + " HTTP "
                                    + result.httpCode);
                            return;
                        }
                        state.entries = LiveModelCatalog.reconcile(result,
                                ModelConfig.modelsForProvider(settings, provider), provider);
                        draft.putCatalog(provider, state.entries);
                        status.setText(UiText.t(
                                "上游返回 " + state.entries.size() + " 个模型，均可直接选择使用。",
                                "The upstream returned " + state.entries.size()
                                        + " models; all are available for selection."));
                        status.setTextColor(state.entries.isEmpty() ? DshUi.WARN() : DshUi.SUCCESS());
                        fill.run();
                        host.log("上游模型目录读取完成: " + provider + "，返回 "
                                + state.entries.size() + " 个，全部允许选择");
                    }
                });
            }
        };
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { DshUi.swapDialog(dialog, true, null); }
        });
        reload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load.run(); }
        });
        DshUi.onBack(dialog, null);
        dialog.show();
        load.run();
    }

    private static void runCheck(final Activity act, final Host host, final Dialog owner,
                                 final String settings,
                                 final Draft draft, final int revision,
                                 final String provider,
                                 final String key, final String selectedModel,
                                 final TextView status, final Button button, final Runnable onCapabilities) {
        if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(provider) && key.length() == 0) {
            DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                    "Add the API key for the selected provider"));
            return;
        }
        DshUi.setBusy(button, UiText.t("检测连接与模型", "Check connection and model"),
                UiText.t("正在检测…", "Checking…"), true);
        status.setText(UiText.t("正在连接服务商…", "Connecting to provider…"));
        status.setTextColor(DshUi.TEXT_2());
        requestCatalog(act, host.coreApi(), provider, key, new CatalogCallback() {
            @Override public void complete(ProviderCheck.Result checked, Throwable error) {
                if (act.isFinishing() || act.isDestroyed() || !owner.isShowing()) return;
                if (draft.checkRevision != revision) return;
                DshUi.setBusy(button,
                        UiText.t("检测连接与模型", "Check connection and model"),
                        UiText.t("正在检测…", "Checking…"), false);
                if (error != null) {
                    status.setText(UiText.t("连接失败：", "Connection failed: ")
                            + safeMessage(error));
                    status.setTextColor(DshUi.ERROR());
                    host.log("服务商检测失败: " + error.getClass().getSimpleName());
                    return;
                }
                List<LiveModelCatalog.Entry> entries = LiveModelCatalog.reconcile(
                        checked, ModelConfig.modelsForProvider(settings, provider), provider);
                if (checked.state == ProviderCheck.READY) {
                    draft.putCatalog(provider, entries);
                    if (onCapabilities != null) onCapabilities.run();
                }
                if (ModelConfig.DEEPSEEK_ACCOUNT.equals(provider) && checked.state == ProviderCheck.KEY_REJECTED) {
                    status.setText(UiText.t("请先在模型中心登录 DeepSeek 账号", "Sign in to DeepSeek in Model center first."));
                    status.setTextColor(DshUi.WARN());
                } else if (ModelConfig.DEEPSEEK_ACCOUNT.equals(provider) && checked.state == ProviderCheck.READY) {
                    status.setText(ProviderCheck.contains(checked, selectedModel)
                            ? UiText.t("账号授权已保存，当前模型已列入账号目录。实际使用取决于账号权限和额度。",
                                    "Authorization is saved and the model is in the account catalog. Usage depends on account permissions and balance.")
                            : UiText.t("账号目录没有当前模型，请重新选择。",
                                    "The account catalog does not include this model. Choose another model."));
                    status.setTextColor(ProviderCheck.contains(checked, selectedModel) ? DshUi.SUCCESS() : DshUi.WARN());
                } else renderCheck(status, checked, entries, selectedModel);
                host.log("服务商检测完成: " + provider + " HTTP "
                        + checked.httpCode + "，模型 " + checked.models.size() + " 个");
            }
        });
    }

    private static void renderCheck(TextView status, ProviderCheck.Result result,
                                    List<LiveModelCatalog.Entry> entries, String model) {
        if (result.state == ProviderCheck.READY) {
            boolean present = ProviderCheck.contains(result, model);
            if (present) {
                status.setText(UiText.t(
                        "连接正常，当前模型来自上游实时目录；共返回 "
                                + result.models.size() + " 个模型，可直接使用。",
                        "Connected. The selected model is in the live upstream catalog; "
                                + result.models.size() + " models were returned and can be used."));
                status.setTextColor(DshUi.SUCCESS());
            } else {
                status.setText(UiText.t("连接正常，但服务商当前列表中没有所选模型。请重新选择。",
                        "Connected, but the selected model is not in the provider's current catalog. Choose another model."));
                status.setTextColor(DshUi.WARN());
            }
        } else if (result.state == ProviderCheck.KEY_REJECTED) {
            status.setText(UiText.t("服务商拒绝访问（HTTP " + result.httpCode
                            + "），请检查密钥及账户 API 权限。",
                    "Provider access was denied (HTTP " + result.httpCode
                            + "). Check your API key and account API access."));
            status.setTextColor(DshUi.ERROR());
        } else if (result.state == ProviderCheck.RATE_LIMITED) {
            status.setText(UiText.t("服务商已识别密钥，但当前触发限流，请稍后重试。",
                    "The provider recognized the request but is rate-limiting it. Try again later."));
            status.setTextColor(DshUi.WARN());
        } else if (result.state == ProviderCheck.ENDPOINT_CHANGED) {
            status.setText(UiText.t("模型列表端点不可用，服务商接口可能已调整。",
                    "The model-list endpoint is unavailable and may have changed."));
            status.setTextColor(DshUi.ERROR());
        } else if (result.state == ProviderCheck.INVALID_RESPONSE) {
            status.setText(UiText.t("服务商已响应（HTTP " + result.httpCode
                            + "），但没有返回可识别的模型列表。原有目录已保留。",
                    "The provider responded (HTTP " + result.httpCode
                            + ") without a recognizable model catalog. Your existing catalog is preserved."));
            status.setTextColor(DshUi.WARN());
        } else {
            status.setText(UiText.t("服务商暂时不可用（HTTP " + result.httpCode + "）。",
                    "The provider is temporarily unavailable (HTTP " + result.httpCode + ")."));
            status.setTextColor(DshUi.ERROR());
        }
    }

    private static void renderCatalogFailure(TextView status, ProviderCheck.Result result) {
        renderCheck(status, result, new ArrayList<LiveModelCatalog.Entry>(), "");
    }

    private static ModelConfig.Selection validOrDefault(ModelConfig.Selection selection) {
        if (selection != null && selection.valid()) return selection;
        return new ModelConfig.Selection(ModelConfig.COMMAND_CODE, "", "medium");
    }

    private static void selectProvider(Draft draft, String provider) {
        draft.rememberChoice();
        draft.provider = ModelConfig.normalizeProvider(provider);
        draft.model = draft.choice(draft.provider);
    }

    private static TextWatcher keyWatcher(final Draft draft, final String provider,
                                          final Runnable invalidateCheck) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                    int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before,
                                                int count) {
                draft.clearCatalog(provider);
                if (invalidateCheck != null) invalidateCheck.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
    }

    private static String selectedKey(String provider, EditText ccKey, EditText dsKey) {
        if (ModelConfig.DEEPSEEK_ACCOUNT.equals(provider)) return "";
        return ModelConfig.DEEPSEEK.equals(provider)
                ? dsKey.getText().toString().trim() : ccKey.getText().toString().trim();
    }

    private static void saveKey(NativeCoreApi api, String baseline, String ref, String value) throws Exception {
        if (value.equals(ModelConfig.readCredentialRef(baseline, ref))) return;
        JSONObject args = new JSONObject().put("ref", ref);
        if (value.length() == 0) api.call("credentials/unset", args);
        else api.call("credentials/set", args.put("value", value));
    }

    private static String reasoningDetails(Draft draft, String settings) {
        for (LiveModelCatalog.Entry entry : draft.catalog(draft.provider))
            if (entry.id.equals(draft.model)) return entry.details;
        for (ModelConfig.Model model : ModelConfig.modelsForProvider(settings, draft.provider))
            if (model.id.equals(draft.model)) return model.details;
        return "";
    }

    private static void renderEfforts(final Activity activity, LinearLayout area,
                                       TextView hint, final Draft draft, String settings) {
        area.removeAllViews();
        String details = reasoningDetails(draft, settings);
        List<String> choices = ModelReasoning.choices(draft.provider, draft.model, details);
        draft.effort = ModelReasoning.preferred(choices, draft.effort);
        boolean defaultOnly = choices.isEmpty();
        boolean defaultAndMax = choices.size() == 2 && choices.contains("off") && choices.contains("max");
        if (defaultOnly) choices.add("off");
        hint.setText(defaultOnly ? UiText.t("此模型没有已声明的强度档位，使用服务商默认。",
                "No adjustable effort is declared; use the provider default.")
                : UiText.t("可选强度：", "Available requests: ") + choices
                    + UiText.t("。max 将原样发送，上游可能拒绝或忽略。",
                        ". max is sent as requested; the provider may reject or ignore it."));
        final List<Button> buttons = new ArrayList<Button>();
        LinearLayout line = null;
        for (int i = 0; i < choices.size(); i++) {
            final String effort = choices.get(i);
            if (i % 3 == 0) { line = row(activity); area.addView(line, DshUi.fullWidth(activity, 4)); }
            Button button = DshUi.toggleButton(activity, defaultOnly || (defaultAndMax && "off".equals(effort))
                    ? UiText.t("服务商默认", "Provider default")
                    : ("max".equals(effort) ? UiText.t("max（请求）", "max (request)") : effort),
                    effort.equals(draft.effort));
            button.setTag(effort);
            buttons.add(button);
            addEqual(line, button, i % 3 == 0 ? 0 : 4);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    draft.effort = effort;
                    for (Button item : buttons) DshUi.setToggleState(item, effort.equals(item.getTag()));
                    DshUi.choiceActivated(v);
                }
            });
        }
    }

    private static LinearLayout row(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static void addEqual(LinearLayout row, Button button, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = DshUi.dp(row.getContext(), leftMargin);
        row.addView(button, params);
    }

    private interface CatalogCallback {
        void complete(ProviderCheck.Result result, Throwable error);
    }

    /** 通过服务商目录或内核账号 RPC 读取模型；回调始终回到主线程。 */
    private static void requestCatalog(final Activity act, final NativeCoreApi api, final String provider,
                                       final String key, final CatalogCallback callback) {
        if (api == null) { callback.complete(null, new java.io.IOException("core unavailable")); return; }
        api.execute(new NativeCoreApi.Work() {
            @Override public Object run() throws Exception { return fetchCatalog(api, provider, key); }
        }, new NativeCoreApi.Callback() {
            @Override public void complete(Object value, Throwable failure) {
                callback.complete((ProviderCheck.Result) value, failure);
            }
        });
    }

    private static ProviderCheck.Result fetchCatalog(NativeCoreApi api, String provider, String key) throws Exception {
        if (ModelConfig.DEEPSEEK_ACCOUNT.equals(provider)) {
            JSONObject state = (JSONObject) api.call("account/getState", new JSONObject());
            if (!"credential-stored".equals(state.optString("status"))) return ProviderCheck.classify(401, "");
            JSONObject catalog = (JSONObject) api.call("session/modelCatalog", new JSONObject());
            JSONArray groups = catalog.getJSONArray("groups"), models = new JSONArray();
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                if (provider.equals(group.optString("id"))) models = group.getJSONArray("models");
            }
            return ProviderCheck.classify(200, new JSONObject().put("data", models).toString());
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(ProviderCheck.endpoint(provider)).openConnection();
        try {
            connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DSHNative-ModelCatalog");
            int code = connection.getResponseCode();
            try (InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return ProviderCheck.classify(code, readLimited(stream, 1024 * 1024));
            }
        } finally { connection.disconnect(); }
    }

    private static String readLimited(InputStream input, int max) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) > 0) {
            total += count;
            if (total > max) throw new java.io.IOException("response too large");
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.length() == 0) {
            message = error == null ? "unknown" : error.getClass().getSimpleName();
        }
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private static final class CatalogDialogState {
        int generation;
        List<LiveModelCatalog.Entry> entries = new ArrayList<LiveModelCatalog.Entry>();
    }

    private static final class Draft {
        boolean globalScope;
        boolean followGlobal;
        String provider;
        String model;
        String effort;
        int checkRevision;
        ModelConfig.Selection baseline;
        final Map<String, String> choices = new LinkedHashMap<String, String>();
        final Map<String, List<LiveModelCatalog.Entry>> catalogs =
                new LinkedHashMap<String, List<LiveModelCatalog.Entry>>();

        void apply(ModelConfig.Selection selection) {
            provider = selection.provider;
            model = selection.model;
            effort = selection.effort;
            baseline = selection;
            rememberChoice();
        }

        void rememberChoice() {
            if (ModelConfig.normalizeProvider(provider).length() > 0
                    && ModelConfig.normalizeModel(model).length() > 0) {
                choices.put(provider, model);
            }
        }

        String choice(String selectedProvider) {
            String value = choices.get(selectedProvider);
            return value == null ? "" : value;
        }

        void putCatalog(String selectedProvider, List<LiveModelCatalog.Entry> entries) {
            catalogs.put(selectedProvider,
                    entries == null ? new ArrayList<LiveModelCatalog.Entry>()
                            : new ArrayList<LiveModelCatalog.Entry>(entries));
        }

        void clearCatalog(String selectedProvider) {
            catalogs.remove(selectedProvider);
        }

        boolean catalogLoaded(String selectedProvider) {
            return catalogs.containsKey(selectedProvider);
        }

        List<LiveModelCatalog.Entry> catalog(String selectedProvider) {
            List<LiveModelCatalog.Entry> entries = catalogs.get(selectedProvider);
            return entries == null ? new ArrayList<LiveModelCatalog.Entry>() : entries;
        }
    }
}
