package com.hmydk.aigit;

import com.hmydk.aigit.constant.Constants;
import com.hmydk.aigit.service.CommitMessageService;
import com.hmydk.aigit.util.GItUtil;
import com.hmydk.aigit.util.IdeaDialogUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.ui.CommitMessage;
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Action 类，用于生成 Git commit 消息
 * 继承自 AnAction 以集成到 IDEA 的操作系统中
 */
public class GenerateCommitMessageAction extends AnAction {

    /**
     * 获取CommitMessage对象
     */
    private CommitMessage getCommitMessage(AnActionEvent e) {
        return (CommitMessage) e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
    }

    private final StringBuilder messageBuilder = new StringBuilder();
    private Timer iconAnimationTimer;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private int currentIconIndex = 0;
    private final Icon originalIcon = IconLoader.getIcon("/icons/git-commit-logo.svg", getClass());
    private final Icon[] progressIcons = new Icon[]{
            AllIcons.Process.Step_1,
            AllIcons.Process.Step_2,
            AllIcons.Process.Step_3,
            AllIcons.Process.Step_4,
            AllIcons.Process.Step_5,
            AllIcons.Process.Step_6,
            AllIcons.Process.Step_7,
            AllIcons.Process.Step_8
    };

    private void startIconAnimation(AnActionEvent e) {
        if (iconAnimationTimer != null) {
            iconAnimationTimer.stop();
        }
        
        iconAnimationTimer = new Timer(100, event -> {
            if (isGenerating.get()) {
                currentIconIndex = (currentIconIndex + 1) % progressIcons.length;
                e.getPresentation().setIcon(progressIcons[currentIconIndex]);
            }
        });
        iconAnimationTimer.start();
        isGenerating.set(true);
    }

    private void stopIconAnimation(AnActionEvent e) {
        if (iconAnimationTimer != null) {
            iconAnimationTimer.stop();
            iconAnimationTimer = null;
        }
        isGenerating.set(false);
        e.getPresentation().setIcon(originalIcon);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        startIconAnimation(e);  // 开始图标动画

        // 根据配置，创建对应的服务
        CommitMessageService commitMessageService = new CommitMessageService();

        if (!commitMessageService.checkNecessaryModuleConfigIsRight()) {
            stopIconAnimation(e);
            IdeaDialogUtil.handleModuleNecessaryConfigIsWrong(project);
            return;
        }

        AbstractCommitWorkflowHandler<?, ?> commitWorkflowHandler = (AbstractCommitWorkflowHandler<?, ?>) e.getData(
                VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        if (commitWorkflowHandler == null) {
            stopIconAnimation(e);
            IdeaDialogUtil.handleNoChangesSelected(project);
            return;
        }

        CommitMessage commitMessage = getCommitMessage(e);

        List<Change> includedChanges = commitWorkflowHandler.getUi().getIncludedChanges();
        List<FilePath> includedUnversionedFiles = commitWorkflowHandler.getUi().getIncludedUnversionedFiles();

        if (includedChanges.isEmpty() && includedUnversionedFiles.isEmpty()) {
            stopIconAnimation(e);
            commitMessage.setCommitMessage(Constants.NO_FILE_SELECTED);
            return;
        }

        commitMessage.setCommitMessage(Constants.GENERATING_COMMIT_MESSAGE);

        // Run the time-consuming operations in a background task
        ProgressManager.getInstance().run(new Task.Backgroundable(project, Constants.TASK_TITLE, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    String diff = GItUtil.computeDiff(includedChanges, includedUnversionedFiles, project);
//                    System.out.println("diff: " + diff);
                    if (commitMessageService.generateByStream()) {
                        messageBuilder.setLength(0);
                        commitMessageService.generateCommitMessageStream(
                                project,
                                diff,
                                // onNext 处理每个token
                                token -> ApplicationManager.getApplication().invokeLater(() -> {
                                    if (messageBuilder.isEmpty()) {
                                        messageBuilder.append(token);
                                        commitMessage.setCommitMessage(token);
                                    } else {
                                        messageBuilder.append(token);
                                        commitMessage.setCommitMessage(messageBuilder.toString());
                                    }
                                }),
                                // onError 处理错误
                                error -> ApplicationManager.getApplication().invokeLater(() -> {
                                    stopIconAnimation(e);
                                }),
                                // onComplete 处理完成
                                () -> ApplicationManager.getApplication().invokeLater(() -> {
                                    stopIconAnimation(e);
                                })
                        );
                    } else {
                        String commitMessageFromAi = commitMessageService.generateCommitMessage(project, diff).trim();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            commitMessage.setCommitMessage(commitMessageFromAi);
                            stopIconAnimation(e);
                        });
                    }
                } catch (IllegalArgumentException ex) {
                    stopIconAnimation(e);
                    IdeaDialogUtil.showWarning(project, ex.getMessage(), "AI Commit Message Warning");
                } catch (Exception ex) {
                    stopIconAnimation(e);
                    IdeaDialogUtil.showError(project, "Error generating commit message: <br>" + getErrorMessage(ex.getMessage()), "Error");
                }
            }

            @Override
            public void onFinished() {
                stopIconAnimation(e);
            }
        });
    }

    private static @NotNull String getErrorMessage(String errorMessage) {
        String append = "<br>This issue is caused by service instability. You can try again.";
        if (errorMessage.contains("429")) {
            errorMessage = "Too many requests. Please try again later.";
            errorMessage += append;
        } else if (errorMessage.contains("Read timeout") || errorMessage.contains("Timeout") || errorMessage.contains("timed out")) {
            errorMessage = "Read timeout. Please try again later. ";
            errorMessage += append;
        } else if (errorMessage.contains("400")) {
            errorMessage = "Bad Request. Please try again later.";
            errorMessage += append;
        } else if (errorMessage.contains("401")) {
            errorMessage = "Unauthorized. Please check your API key.";
        } else if (errorMessage.contains("403")) {
            errorMessage = "Forbidden. Please check your API key.";
        } else if (errorMessage.contains("404")) {
            errorMessage = "Not Found. Please check your API key.";
        } else if (errorMessage.contains("500")) {
            errorMessage = "Internal Server Error. Please try again later.";
            errorMessage += append;
        } else if (errorMessage.contains("502")) {
            errorMessage = "Bad Gateway. Please try again later.";
            errorMessage += append;
        } else if (errorMessage.contains("503")) {
            errorMessage = "Service Unavailable. Please try again later.";
            errorMessage += append;
        } else if (errorMessage.contains("504")) {
            errorMessage = "Gateway Timeout. Please try again later.";
            errorMessage += append;
        }
        return errorMessage;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // 控制 Action 的启用/禁用状态
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // 指定在后台线程更新 Action 状态，提高性能
        return ActionUpdateThread.BGT;
    }

}