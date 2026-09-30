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
        File dshHome();
        String activeProject();
        void log(String message);
        void openCommandCodeUsage(String key);
        /** 让 DSH WebUI 重新读取刚写入的模型目录；任务运行时由宿主自行延后。 */
        void refreshModelCatalog();
        void closeModelCenter(boolean onboarding, boolean saved);
    }


    private ModelCenterPanel() { }

    private static boolean refreshingCatalog;

    /** Update saved provider catalogs without changing the current default model. */
    static void refreshConfigured(final Activity activity, final Host host) {
        if (refreshingCatalog) {
            DshUi.toast(activity, UiText.t("正在更新模型目录…", "Updating the model catalog…"));
            return;
        }
        try {
            String credentials = ProjectModelSettings.readFile(new File(host.dshHome(), ".credentials.yaml"));
            if (ModelConfig.readCredentialRef(credentials, "COMMANDCODE_API_KEY").length() == 0
                    && ModelConfig.readCredentialRef(credentials, "DEEPSEEK_API_KEY").length() == 0) {
                show(activity, host, false);
                return;
            }
            refreshingCatalog = true;
            DshUi.toast(activity, UiText.t("正在读取并同步上游模型…", "Fetching and syncing upstream models…"));
            refreshProvider(activity, host, 0, 0, new ArrayList<String>());
        } catch (Throwable error) {
            refreshingCatalog = false;
            host.log("模型目录更新失败: " + error.getClass().getSimpleName());
            DshUi.toast(activity, UiText.t("模型目录更新失败", "Model catalog update failed"));
        }
    }

    private static void refreshProvider(final Activity activity, final Host host,
                                         final int index, final int synced,
                                         final List<String> failures) {
        final String[] providers = {ModelConfig.COMMAND_CODE, ModelConfig.DEEPSEEK};
        if (index >= providers.length) {
            refreshingCatalog = false;
            if (!failures.isEmpty()) DshUi.toast(activity, UiText.t("更新失败：", "Update failed: ") + failures);
            if (synced > 0) host.refreshModelCatalog();
            return;
        }
        final String provider = providers[index];
        try {
            String credentials = ProjectModelSettings.readFile(new File(host.dshHome(), ".credentials.yaml"));
            final String key = ModelConfig.readCredentialRef(credentials, ModelConfig.credentialKey(provider));
            if (key.length() == 0) {
                refreshProvider(activity, host, index + 1, synced, failures);
                return;
            }
            requestCatalog(activity, provider, key, new CatalogCallback() {
                @Override public void complete(ProviderCheck.Result result, Throwable error) {
                    int updated = synced;
                    try {
                        if (error != null || result == null || result.state != ProviderCheck.READY)
                            throw new java.io.IOException(error == null && result != null
                                    ? "HTTP " + result.httpCode : "network error");
                        File credentialsFile = new File(host.dshHome(), ".credentials.yaml");
                        String latestKey = ModelConfig.readCredentialRef(
                                ProjectModelSettings.readFile(credentialsFile), ModelConfig.credentialKey(provider));
                        if (!key.equals(latestKey)) throw new java.io.IOException("credentials changed; retry");
                        File settingsFile = new File(host.dshHome(), "settings.yaml");
                        String settings = ProjectModelSettings.readFile(settingsFile);
                        List<LiveModelCatalog.Entry> entries = LiveModelCatalog.reconcile(result,
                                ModelConfig.modelsForProvider(settings, provider), provider);
                        String next = ModelCatalogSync.writeLiveCatalog(settings, provider, entries);
                        ProjectModelSettings.writeFileAtomic(settingsFile, next);
                        updated++;
                        host.log("已同步上游模型到 DSH: " + provider + "，" + entries.size() + " 个");
                    } catch (Throwable failure) {
                        failures.add(ModelConfig.providerName(provider, UiText.isEnglish()));
                        host.log("模型目录同步失败: " + provider + " / " + failure.getClass().getSimpleName());
                    }
                    refreshProvider(activity, host, index + 1, updated, failures);
                }
            });
        } catch (Throwable error) {
            failures.add(ModelConfig.providerName(provider, UiText.isEnglish()));
            refreshProvider(activity, host, index + 1, synced, failures);
        }
    }

    static void show(Activity activity, Host host, boolean onboarding) {
        show(activity, host, host.activeProject(), onboarding, false);
    }

    static void showProject(Activity activity, Host host, String project) {
        show(activity, host, project, false, true);
    }

    private static void show(final Activity act, final Host host, final String project,
                             final boolean onboarding, final boolean projectOnly) {
        try {
            final File home = host.dshHome();
            final File settingsFile = new File(home, "settings.yaml");
            final File credentialsFile = new File(home, ".credentials.yaml");
            final File projectsFile = new File(home, ProjectModelSettings.FILE_NAME);
            final String settingsText = ProjectModelSettings.readFile(settingsFile);
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
                    ? UiText.t("选择服务商、填写密钥并确认默认模型。检测只读取模型列表，不会产生模型调用费用。",
                            "Choose a provider, add its key, and confirm a default model. The check only reads the model list and does not make a billed model call.")
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
                    UiText.t("DeepSeek 官方", "DeepSeek direct"),
                    ModelConfig.DEEPSEEK.equals(draft.provider));
            LinearLayout providerRow = row(act);
            addEqual(providerRow, cc, 0);
            addEqual(providerRow, ds, 6);
            body.addView(providerRow, DshUi.fullWidth(act, 6));

            body.addView(DshUi.label(act, "Command Code API Key"), DshUi.fullWidth(act, 14));
            final EditText ccKey = DshUi.input(act, ModelConfig.readCredentialRef(
                    credentialsText, "COMMANDCODE_API_KEY"), true);
            body.addView(ccKey, DshUi.fullWidth(act, 6));
            body.addView(DshUi.label(act, "DeepSeek API Key"), DshUi.fullWidth(act, 14));
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
                    UiText.t("更新上游模型列表", "Update upstream model list"), false);
            body.addView(updateCatalog, DshUi.fullWidth(act, 6));
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
                        if (savedKey.length() == 0 || !savedKey.equals(selectedKey(draft.provider, ccKey, dsKey))) {
                            openChooser.onClick(v);
                        } else refreshConfigured(act, host);
                    } catch (Throwable error) {
                        DshUi.toast(act, UiText.t("读取已保存的账号失败", "Could not read the saved account"));
                    }
                }
            });
            model.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showModelChooser(act, host, dialog, settingsText, draft, model,
                            selectedKey(draft.provider, ccKey, dsKey), refresh);
                }
            });
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
                    ModelConfig.Selection selection = new ModelConfig.Selection(
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
                    if (selectedKey.length() == 0) {
                        DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                                "Add the API key for the selected provider"));
                        return;
                    }
                    final String saveIdle = onboarding
                            ? UiText.t("保存并完成", "Save and finish")
                            : UiText.t("保存", "Save");
                    DshUi.setBusy(save, saveIdle,
                            UiText.t("保存中", "Saving"), true);
                    try {
                        String latestSettings = ProjectModelSettings.readFile(settingsFile);
                        String latestCredentials = ProjectModelSettings.readFile(credentialsFile);
                        ProjectModelSettings.State latestState = ProjectModelSettings.parse(
                                ProjectModelSettings.readFile(projectsFile));
                        ModelConfig.Selection latestFileSelection =
                                ModelConfig.readSelection(latestSettings);
                        if (latestState.global == null || !latestState.global.valid()) {
                            ProjectModelSettings.setGlobal(latestState,
                                    projectState.global != null && projectState.global.valid()
                                            ? projectState.global : latestFileSelection);
                        }
                        if (draft.globalScope || onboarding) {
                            ProjectModelSettings.setGlobal(latestState, selection);
                        } else if (draft.followGlobal) {
                            ProjectModelSettings.clearOverride(latestState, project);
                        } else {
                            ProjectModelSettings.setOverride(latestState, project, selection);
                        }
                        ModelConfig.Selection effective = ProjectModelSettings.effective(
                                latestState, project, latestFileSelection);
                        String nextSettings = ModelConfig.updateSelection(latestSettings, effective);
                        List<LiveModelCatalog.Entry> liveEntries = draft.catalog(selection.provider);
                        if (draft.catalogLoaded(selection.provider)) {
                            nextSettings = ModelCatalogSync.writeLiveCatalog(nextSettings,
                                    selection.provider, liveEntries);
                        }
                        String nextCredentials = ModelConfig.updateCredentialRef(latestCredentials,
                                "COMMANDCODE_API_KEY", ccKey.getText().toString().trim());
                        nextCredentials = ModelConfig.updateCredentialRef(nextCredentials,
                                "DEEPSEEK_API_KEY", dsKey.getText().toString().trim());
                        ProjectModelSettings.writeFileAtomic(projectsFile,
                                ProjectModelSettings.serialize(latestState));
                        ProjectModelSettings.writeFileAtomic(settingsFile, nextSettings);
                        ProjectModelSettings.writeFileAtomic(credentialsFile, nextCredentials);
                        host.refreshModelCatalog();
                        host.log("模型配置已保存: " + selection.provider + " / "
                                + selection.model + " / " + selection.effort
                                + (draft.globalScope || onboarding ? "（全局）"
                                : draft.followGlobal ? "（跟随全局）" : "（项目覆盖）"));
                        dialog.dismiss();
                        host.closeModelCenter(onboarding, true);
                    } catch (Throwable error) {
                        host.log("模型配置保存失败: " + error.getClass().getSimpleName());
                        DshUi.setBusy(save, saveIdle,
                                UiText.t("保存中", "Saving"), false);
                        DshUi.toast(act, UiText.t("保存失败：", "Save failed: ")
                                + safeMessage(error));
                    }
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
        if (key.length() == 0) {
            DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                    "Add the API key for the selected provider"));
            return;
        }
        final String provider = draft.provider;
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, UiText.t("选择模型", "Choose model")));
        body.addView(DshUi.hint(act, UiText.t(
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
        final Dialog dialog = DshUi.dialog(act, DshUi.scroll(act, body),
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
                requestCatalog(act, provider, key, new CatalogCallback() {
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
                            renderCatalogFailure(status, result);
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
        if (key.length() == 0) {
            DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                    "Add the API key for the selected provider"));
            return;
        }
        DshUi.setBusy(button, UiText.t("检测连接与模型", "Check connection and model"),
                UiText.t("正在检测…", "Checking…"), true);
        status.setText(UiText.t("正在连接服务商…", "Connecting to provider…"));
        status.setTextColor(DshUi.TEXT_2());
        requestCatalog(act, provider, key, new CatalogCallback() {
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
                renderCheck(status, checked, entries, selectedModel);
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
            status.setText(UiText.t("密钥被拒绝，请检查是否复制完整或是否具有 API 权限。",
                    "The key was rejected. Check that it is complete and has API access."));
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
            status.setText(UiText.t("服务商已响应，但没有返回可识别的模型列表。",
                    "The provider responded without a recognizable model catalog."));
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
        return ModelConfig.DEEPSEEK.equals(provider)
                ? dsKey.getText().toString().trim() : ccKey.getText().toString().trim();
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
        if (defaultOnly) choices.add("off");
        hint.setText(defaultOnly ? UiText.t("此模型没有已声明的强度档位，使用服务商默认。",
                "No adjustable effort is declared; use the provider default.")
                : UiText.t("当前模型支持：", "Supported by this model: ") + choices);
        final List<Button> buttons = new ArrayList<Button>();
        LinearLayout line = null;
        for (int i = 0; i < choices.size(); i++) {
            final String effort = choices.get(i);
            if (i % 3 == 0) { line = row(activity); area.addView(line, DshUi.fullWidth(activity, 4)); }
            Button button = DshUi.toggleButton(activity, defaultOnly
                    ? UiText.t("服务商默认", "Provider default") : effort, effort.equals(draft.effort));
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

    /** 发起只读 GET /models；回调始终回到主线程。 */
    private static void requestCatalog(final Activity act, final String provider,
                                       final String key, final CatalogCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                ProviderCheck.Result result = null;
                Throwable failure = null;
                HttpURLConnection connection = null;
                InputStream stream = null;
                try {
                    connection = (HttpURLConnection) new URL(ProviderCheck.endpoint(provider))
                            .openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(15000);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("Authorization", "Bearer " + key);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "DSHNative-ModelCatalog");
                    int code = connection.getResponseCode();
                    stream = code >= 400
                            ? connection.getErrorStream() : connection.getInputStream();
                    result = ProviderCheck.classify(code, readLimited(stream, 1024 * 1024));
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    if (stream != null) {
                        try { stream.close(); } catch (Throwable ignored) { }
                    }
                    if (connection != null) connection.disconnect();
                }
                final ProviderCheck.Result completed = result;
                final Throwable error = failure;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (callback != null) callback.complete(completed, error);
                    }
                });
            }
        }, "provider-catalog").start();
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
