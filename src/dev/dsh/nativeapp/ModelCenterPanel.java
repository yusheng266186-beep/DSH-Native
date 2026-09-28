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
import java.util.List;

/** 模型中心、服务商检测、项目覆盖和首次账号配置。 */
final class ModelCenterPanel {
    interface Host {
        File dshHome();
        String activeProject();
        void log(String message);
        void openCommandCodeUsage(String key);
        void closeModelCenter(boolean onboarding, boolean saved);
    }

    private static final String[] EFFORTS = {"off", "low", "medium", "high", "xhigh", "max"};

    private ModelCenterPanel() { }

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
            draft.apply(validOrDefault(settingsText, initial));

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
            model.setHint(UiText.t("从已配置模型中选择", "Choose a configured model"));
            body.addView(model, DshUi.fullWidth(act, 6));
            final Button choose = DshUi.button(act,
                    UiText.t("选择模型", "Choose model"), false);
            body.addView(choose, DshUi.fullWidth(act, 6));

            body.addView(DshUi.sectionLabel(act,
                    UiText.t("思考强度", "Reasoning effort")), DshUi.fullWidth(act, 20));
            final List<Button> effortButtons = new ArrayList<Button>();
            for (int i = 0; i < EFFORTS.length; i++) {
                final String effort = EFFORTS[i];
                Button button = DshUi.toggleButton(act, effort, effort.equals(draft.effort));
                effortButtons.add(button);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        draft.followGlobal = false;
                        draft.effort = effort;
                        updateEfforts(effortButtons, draft.effort);
                        DshUi.choiceActivated(v);
                    }
                });
            }
            LinearLayout effortTop = row(act);
            LinearLayout effortBottom = row(act);
            for (int i = 0; i < effortButtons.size(); i++) {
                addEqual(i < 3 ? effortTop : effortBottom, effortButtons.get(i),
                        i == 0 || i == 3 ? 0 : 4);
            }
            body.addView(effortTop, DshUi.fullWidth(act, 6));
            body.addView(effortBottom, DshUi.fullWidth(act, 4));

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
                    ? UiText.t("稍后设置", "Set up later") : UiText.t("返回", "Back"), false);
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
                    updateEfforts(effortButtons, draft.effort);
                }
            };

            TextWatcher keyChanged = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                        int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before,
                                                    int count) {
                    invalidateCheck.run();
                }
                @Override public void afterTextChanged(Editable s) { }
            };
            ccKey.addTextChangedListener(keyChanged);
            dsKey.addTextChangedListener(keyChanged);

            cc.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    selectProvider(settingsText, draft, ModelConfig.COMMAND_CODE);
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            ds.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    selectProvider(settingsText, draft, ModelConfig.DEEPSEEK);
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            global.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    if (projectOnly) return;
                    draft.globalScope = true;
                    draft.apply(validOrDefault(settingsText, projectState.global));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            perProject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = false;
                    draft.globalScope = false;
                    draft.apply(validOrDefault(settingsText,
                            ProjectModelSettings.effective(projectState, project, fileSelection)));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            followGlobal.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.followGlobal = true;
                    draft.apply(validOrDefault(settingsText, projectState.global));
                    refresh.run();
                    DshUi.choiceActivated(v);
                }
            });
            choose.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
                    showModelChooser(act, settingsText, draft, model, invalidateCheck);
                }
            });
            model.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showModelChooser(act, settingsText, draft, model, invalidateCheck);
                }
            });
            check.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
                    String key = ModelConfig.DEEPSEEK.equals(draft.provider)
                            ? dsKey.getText().toString().trim()
                            : ccKey.getText().toString().trim();
                    runCheck(act, host, dialog, draft, draft.checkRevision,
                            draft.provider, key, draft.model, checkStatus, check);
                }
            });
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    host.closeModelCenter(onboarding, false);
                }
            });
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft.model = model.getText().toString().trim();
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
                    if (!ModelConfig.containsModel(settingsText, selection.provider, selection.model)) {
                        DshUi.toast(act, UiText.t("该模型不在当前配置清单中，请使用“选择模型”",
                                "This model is not in the configured catalog. Use Choose model."));
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
                                latestState, host.activeProject(), latestFileSelection);
                        String nextSettings = ModelConfig.updateSelection(latestSettings, effective);
                        String nextCredentials = ModelConfig.updateCredentialRef(latestCredentials,
                                "COMMANDCODE_API_KEY", ccKey.getText().toString().trim());
                        nextCredentials = ModelConfig.updateCredentialRef(nextCredentials,
                                "DEEPSEEK_API_KEY", dsKey.getText().toString().trim());
                        ProjectModelSettings.writeFileAtomic(projectsFile,
                                ProjectModelSettings.serialize(latestState));
                        ProjectModelSettings.writeFileAtomic(settingsFile, nextSettings);
                        ProjectModelSettings.writeFileAtomic(credentialsFile, nextCredentials);
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

            refresh.run();
            dialog.setCancelable(!onboarding);
            dialog.show();
        } catch (Throwable error) {
            host.log("打开模型中心失败: " + error);
            DshUi.toast(act, UiText.t("打开模型中心失败", "Could not open model center"));
            host.closeModelCenter(onboarding, false);
        }
    }

    private static void showModelChooser(final Activity act, String settings,
                                         final Draft draft, final EditText target,
                                         final Runnable onSelection) {
        final List<ModelConfig.Model> models = ModelConfig.modelsForProvider(settings, draft.provider);
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, UiText.t("选择模型", "Choose model")));
        final EditText search = DshUi.input(act, "", false);
        search.setSingleLine(true);
        search.setHint(UiText.t("搜索名称或模型 ID", "Search name or model ID"));
        body.addView(search, DshUi.fullWidth(act, 6));
        final TextView count = DshUi.hint(act, "");
        body.addView(count, DshUi.fullWidth(act, 5));
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, DshUi.fullWidth(act, 5));
        Button close = DshUi.button(act, UiText.t("关闭", "Close"), true);
        final Dialog dialog = DshUi.dialog(act, DshUi.scroll(act, body),
                DshUi.footer(act, close), 720);
        final Runnable fill = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                int matched = 0;
                int shown = 0;
                for (final ModelConfig.Model item : models) {
                    String haystack = (item.id + " " + item.name).toLowerCase(java.util.Locale.ROOT);
                    if (query.length() > 0 && !haystack.contains(query)) continue;
                    matched++;
                    if (shown >= 40) continue;
                    String suffix = (item.image ? UiText.t(" · 图片", " · vision") : "")
                            + (item.reasoning ? UiText.t(" · 思考", " · reasoning") : "");
                    Button button = DshUi.button(act, item.name + "\n" + item.id + suffix,
                            item.id.equals(draft.model));
                    button.setAllCaps(false);
                    button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                    button.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            draft.followGlobal = false;
                            draft.model = item.id;
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
                count.setText(UiText.t("找到 " + matched + " 个模型"
                                + (matched > shown ? "，显示前 " + shown + " 个" : ""),
                        matched + " models" + (matched > shown ? ", showing first " + shown : "")));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { fill.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        fill.run();
        dialog.show();
    }

    private static void runCheck(final Activity act, final Host host, final Dialog owner,
                                 final Draft draft, final int revision,
                                 final String provider,
                                 final String key, final String selectedModel,
                                 final TextView status, final Button button) {
        if (key.length() == 0) {
            DshUi.toast(act, UiText.t("请先填写当前服务商的 API Key",
                    "Add the API key for the selected provider"));
            return;
        }
        DshUi.setBusy(button, UiText.t("检测连接与模型", "Check connection and model"),
                UiText.t("正在检测…", "Checking…"), true);
        status.setText(UiText.t("正在连接服务商…", "Connecting to provider…"));
        status.setTextColor(DshUi.TEXT_2());
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
                    connection.setRequestProperty("User-Agent", "DSHNative-ModelCheck");
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
                final ProviderCheck.Result checked = result;
                final Throwable error = failure;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing() || act.isDestroyed() || !owner.isShowing()) {
                            return;
                        }
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
                        renderCheck(status, checked, selectedModel);
                        host.log("服务商检测完成: " + provider + " HTTP "
                                + checked.httpCode + "，模型 " + checked.models.size() + " 个");
                    }
                });
            }
        }, "provider-check").start();
    }

    private static void renderCheck(TextView status, ProviderCheck.Result result, String model) {
        if (result.state == ProviderCheck.READY) {
            boolean present = ProviderCheck.contains(result, model);
            status.setText(present
                    ? UiText.t("连接正常，当前模型可用；共返回 " + result.models.size() + " 个模型。",
                            "Connected. The selected model is available; " + result.models.size() + " models returned.")
                    : UiText.t("连接正常，但服务商当前列表中没有所选模型。请重新选择。",
                            "Connected, but the selected model is not in the provider's current catalog. Choose another model."));
            status.setTextColor(present ? DshUi.SUCCESS() : DshUi.WARN());
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

    private static ModelConfig.Selection validOrDefault(String settings,
                                                         ModelConfig.Selection selection) {
        if (selection != null && selection.valid()
                && ModelConfig.containsModel(settings, selection.provider, selection.model)) {
            return selection;
        }
        List<ModelConfig.Model> command = ModelConfig.modelsForProvider(
                settings, ModelConfig.COMMAND_CODE);
        if (!command.isEmpty()) {
            return new ModelConfig.Selection(ModelConfig.COMMAND_CODE,
                    command.get(0).id, "medium");
        }
        return new ModelConfig.Selection(ModelConfig.DEEPSEEK, "deepseek-v4-flash", "medium");
    }

    private static void selectProvider(String settings, Draft draft, String provider) {
        draft.provider = provider;
        if (!ModelConfig.containsModel(settings, provider, draft.model)) {
            List<ModelConfig.Model> models = ModelConfig.modelsForProvider(settings, provider);
            draft.model = models.isEmpty() ? "" : models.get(0).id;
        }
    }

    private static void updateEfforts(List<Button> buttons, String selected) {
        for (int i = 0; i < buttons.size() && i < EFFORTS.length; i++) {
            DshUi.setToggleState(buttons.get(i), EFFORTS[i].equals(selected));
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

    private static final class Draft {
        boolean globalScope;
        boolean followGlobal;
        String provider;
        String model;
        String effort;
        int checkRevision;

        void apply(ModelConfig.Selection selection) {
            provider = selection.provider;
            model = selection.model;
            effort = selection.effort;
        }
    }
}
