/*
 * Copyright 2000-2008 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.tfsIntegration.core;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.checkin.CheckinChangeListSpecificComponent;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.MultiLineTooltipUI;
import com.intellij.ui.components.labels.BoldLabel;
import com.intellij.util.ui.UIUtil;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.*;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.checkin.CheckinParameters;
import org.jetbrains.tfsIntegration.core.tfs.*;
import org.jetbrains.tfsIntegration.core.tfs.operations.ScheduleForAddition;
import org.jetbrains.tfsIntegration.core.tfs.operations.ScheduleForDeletion;
import org.jetbrains.tfsIntegration.core.tfs.workitems.WorkItem;
import org.jetbrains.tfsIntegration.exceptions.OperationFailedException;
import org.jetbrains.tfsIntegration.exceptions.TfsException;
import org.jetbrains.tfsIntegration.ui.CheckinParametersDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.List;
import java.util.*;

public class TFSCheckinEnvironment implements CheckinEnvironment {
  @NotNull private final TFSVcs myVcs;

  public TFSCheckinEnvironment(@NotNull TFSVcs vcs) {
    myVcs = vcs;
  }

  @NotNull
  @Override
  public RefreshableOnComponent createCommitOptions(@NotNull CheckinProjectPanel commitPanel, @NotNull CommitContext commitContext) {
    final JComponent panel = new JPanel();
    panel.setLayout(new BorderLayout(5, 0));

    myVcs.getCheckinData().messageLabel = new BoldLabel() {

      @Override
      public JToolTip createToolTip() {
        JToolTip toolTip = new JToolTip() {{
          setUI(new MultiLineTooltipUI());
        }};
        toolTip.setComponent(this);
        return toolTip;
      }
    };

    panel.add(myVcs.getCheckinData().messageLabel, BorderLayout.WEST);

    final JButton configureButton = new JButton("Configure...");
    panel.add(configureButton, BorderLayout.EAST);

    configureButton.addActionListener(new ActionListener() {

      @Override
      public void actionPerformed(final ActionEvent event) {
        CheckinParameters copy = myVcs.getCheckinData().parameters.createCopy();

        CheckinParametersDialog d = new CheckinParametersDialog(commitPanel.getProject(), copy);
        if (d.showAndGet()) {
          myVcs.getCheckinData().parameters = copy;
          updateMessage(myVcs.getCheckinData());
        }
      }
    });

    return new TFSAdditionalOptionsPanel(panel, commitPanel, configureButton);
  }

  public static void updateMessage(TFSVcs.CheckinData checkinData) {
    if (checkinData.parameters == null) {
      return;
    }

    final Pair<String, CheckinParameters.Severity> message = checkinData.parameters.getValidationMessage(CheckinParameters.Severity.BOTH);
    if (message == null) {
      checkinData.messageLabel.setText("<html>Ready to commit</html>"); // prevent bold
      checkinData.messageLabel.setIcon(null);
      checkinData.messageLabel.setToolTipText(null);
    }
    else {
      checkinData.messageLabel.setToolTipText(message.first);
      if (message.second == CheckinParameters.Severity.ERROR) {
        checkinData.messageLabel.setText("Errors found");
        checkinData.messageLabel.setIcon(UIUtil.getBalloonErrorIcon());
      }
      else {
        checkinData.messageLabel.setText("Warnings found");
        checkinData.messageLabel.setIcon(UIUtil.getBalloonWarningIcon());
      }
    }
  }

  @Override
  @Nullable
  @NonNls
  public String getHelpId() {
    return null;  // TODO: help id for check in
  }

  @Override
  public String getCheckinOperationName() {
    return "Checkin";
  }

  @NotNull
  @Override
  public List<VcsException> commit(@NotNull List<? extends Change> changes,
                                   @NotNull String commitMessage,
                                   @NotNull CommitContext commitContext,
                                   @NotNull Set<? super String> feedback) {
    myVcs.getCheckinData().messageLabel = null;

    final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
    final List<FilePath> files = new ArrayList<>();
    for (Change change : changes) {
      FilePath path = null;
      ContentRevision beforeRevision = change.getBeforeRevision();
      ContentRevision afterRevision = change.getAfterRevision();
      if (afterRevision != null) {
        path = afterRevision.getFile();
      }
      else if (beforeRevision != null) {
        path = beforeRevision.getFile();
      }
      if (path != null) {
        files.add(path);
      }
    }
    final List<VcsException> errors = new ArrayList<>();
    try {
      WorkstationHelper.processByWorkspaces(files, false, myVcs.getProject(), new WorkstationHelper.VoidProcessDelegate() {
        @Override
        public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) throws TfsException {
          try {
            TFSProgressUtil.setProgressText(progressIndicator, TFSBundle.message("loading.pending.changes"));
            // get pending changes for given items
            Collection<PendingChange> pendingChanges = workspace.getServer().getVCS()
              .queryPendingSetsByLocalPaths(workspace.getName(), workspace.getOwnerName(), paths, RecursionType.None, myVcs.getProject(),
                                            TFSBundle.message("loading.pending.changes"));

            if (pendingChanges.isEmpty()) {
              return;
            }

            Collection<String> checkIn = new ArrayList<>();
            // upload files
            TFSProgressUtil.setProgressText(progressIndicator, TFSBundle.message("uploading.files"));
            for (PendingChange pendingChange : pendingChanges) {
              if (pendingChange.getType() == ItemType.File) {
                ChangeTypeMask changeType = new ChangeTypeMask(pendingChange.getChg());
                if (changeType.contains(ChangeType_type0.Edit) || changeType.contains(ChangeType_type0.Add)) {
                  TFSProgressUtil
                    .setProgressText2(progressIndicator, VersionControlPath.localPathFromTfsRepresentation(pendingChange.getLocal()));
                  workspace.getServer().getVCS()
                    .uploadItem(workspace, pendingChange, myVcs.getProject(), null);
                }
              }
              checkIn.add(pendingChange.getItem());
            }
            TFSProgressUtil.setProgressText2(progressIndicator, "");

            final WorkItemsCheckinParameters state = myVcs.getCheckinData().parameters.getWorkItems(workspace.getServer());
            final Map<WorkItem, CheckinWorkItemAction> workItemActions =
              state != null ? state.getWorkItemsActions() : Collections.emptyMap();

            List<Pair<String, String>> checkinNotes =
              new ArrayList<>(myVcs.getCheckinData().parameters.getCheckinNotes(workspace.getServer()).size());
            for (CheckinParameters.CheckinNote checkinNote : myVcs.getCheckinData().parameters.getCheckinNotes(workspace.getServer())) {
              checkinNotes.add(Pair.create(checkinNote.name, StringUtil.notNullize(checkinNote.value)));
            }

            TFSProgressUtil.setProgressText(progressIndicator, TFSBundle.message("checking.in"));
            ResultWithFailures<CheckinResult> result = workspace.getServer().getVCS()
              .checkIn(workspace.getName(), workspace.getOwnerName(), checkIn, commitMessage, workItemActions, checkinNotes,
                       myVcs.getCheckinData().parameters.getPolicyOverride(workspace.getServer()), myVcs.getProject(), null);
            errors.addAll(TfsUtil.getVcsExceptions(result.getFailures()));

            Collection<String> commitFailed = new ArrayList<>(result.getFailures().size());
            for (Failure failure : result.getFailures()) {
              TFSVcs.assertTrue(failure.getItem() != null);
              commitFailed.add(failure.getItem());
            }

            Collection<FilePath> invalidateRoots = new ArrayList<>(pendingChanges.size());
            Collection<FilePath> invalidateFiles = new ArrayList<>();
            // set readonly status for files
            Collection<VirtualFile> makeReadOnly = new ArrayList<>();
            for (PendingChange pendingChange : pendingChanges) {
              TFSVcs.assertTrue(pendingChange.getItem() != null);
              if (commitFailed.contains(pendingChange.getItem())) {
                continue;
              }

              ChangeTypeMask changeType = new ChangeTypeMask(pendingChange.getChg());
              if (pendingChange.getType() == ItemType.File) {
                if (changeType.contains(ChangeType_type0.Edit) ||
                    changeType.contains(ChangeType_type0.Add) ||
                    changeType.contains(ChangeType_type0.Rename)) {
                  VirtualFile file = VersionControlPath.getVirtualFile(pendingChange.getLocal());
                  if (file != null && file.isValid()) {
                    makeReadOnly.add(file);
                  }
                }
              }

              // TODO don't add recursive invalidate
              // TODO if Rename, invalidate old and new items?
              final FilePath path = VersionControlPath.getFilePath(pendingChange.getLocal(), pendingChange.getType() == ItemType.Folder);
              invalidateRoots.add(path);
              if (changeType.contains(ChangeType_type0.Add) || changeType.contains(ChangeType_type0.Rename)) {
                // [IDEADEV-27087] invalidate parent folders since they can be implicitly checked in with child checkin
                final VirtualFile vcsRoot = ProjectLevelVcsManager.getInstance(myVcs.getProject()).getVcsRootFor(path);
                if (vcsRoot != null) {
                  final FilePath vcsRootPath = TfsFileUtil.getFilePath(vcsRoot);
                  for (FilePath parent = path.getParentPath();
                       parent != null && parent.isUnder(vcsRootPath, false);
                       parent = parent.getParentPath()) {
                    invalidateFiles.add(parent);
                  }
                }
              }
            }

            TfsFileUtil.setReadOnly(makeReadOnly, true);

            TFSProgressUtil.setProgressText(progressIndicator, TFSBundle.message("updating.work.items"));
            if (commitFailed.isEmpty()) {
              CheckinResult checkinResult = result.getResult().iterator().next();
              workspace.getServer().getVCS()
                .updateWorkItemsAfterCheckin(workspace.getOwnerName(), workItemActions, checkinResult.getCset(), myVcs.getProject(),
                                             null);
            }

            TfsFileUtil.markDirty(myVcs.getProject(), invalidateRoots, invalidateFiles);
          }
          catch (IOException e) {
            errors.add(new VcsException(e));
          }
        }
      });
    }
    catch (TfsException e) {
      errors.add(new VcsException(e));
    }
    myVcs.getCheckinData().parameters = null;
    myVcs.fireRevisionChanged();
    return errors;
  }

  @Override
  @Nullable
  public List<VcsException> scheduleMissingFileForDeletion(@NotNull final List<? extends FilePath> files) {
    final List<VcsException> errors = new ArrayList<>();
    try {
      WorkstationHelper.processByWorkspaces(files, false, myVcs.getProject(), new WorkstationHelper.VoidProcessDelegate() {
        @Override
        public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) {
          Collection<VcsException> schedulingErrors = ScheduleForDeletion.execute(myVcs.getProject(), workspace, paths);
          errors.addAll(schedulingErrors);
        }
      });
    }
    catch (TfsException e) {
      errors.add(new VcsException(e));
    }
    return errors;
  }

  @Override
  @Nullable
  public List<VcsException> scheduleUnversionedFilesForAddition(@NotNull final List<? extends VirtualFile> files) {
    // TODO: schedule parent folders?
    final List<VcsException> exceptions = new ArrayList<>();
    try {
      final List<FilePath> orphans =
        WorkstationHelper
          .processByWorkspaces(TfsFileUtil.getFilePaths(files), false, myVcs.getProject(), new WorkstationHelper.VoidProcessDelegate() {
            @Override
            public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) {
              Collection<VcsException> schedulingErrors = ScheduleForAddition.execute(myVcs.getProject(), workspace, paths);
              exceptions.addAll(schedulingErrors);
            }
          });
      if (!orphans.isEmpty()) {
        StringBuilder s = new StringBuilder();
        for (FilePath orpan : orphans) {
          if (s.length() > 0) {
            s.append("\n");
          }
          s.append(orpan.getPresentableUrl());
        }
        exceptions.add(new VcsException("Team Foundation Server mapping not found for: " + s.toString()));
      }
    }
    catch (TfsException e) {
      exceptions.add(new VcsException(e));
    }
    return exceptions;
  }

  @Override
  public boolean isRefreshAfterCommitNeeded() {
    return true;
  }

  // TODO refactor this class
  private class TFSAdditionalOptionsPanel implements CheckinChangeListSpecificComponent {
    private final JComponent myPanel;
    private final CheckinProjectPanel myCheckinProjectPanel;
    private final JButton myConfigureButton;
    private LocalChangeList myCurrentList;

    TFSAdditionalOptionsPanel(JComponent panel, CheckinProjectPanel checkinProjectPanel, JButton configureButton) {
      myPanel = panel;
      myCheckinProjectPanel = checkinProjectPanel;
      myConfigureButton = configureButton;
    }

    @Override
    public JComponent getComponent() {
      return myPanel;
    }

    @Override
    public void refresh() {
    }

    @Override
    public void saveState() {
    }

    @Override
    public void restoreState() {
    }

    @Override
    public void onChangeListSelected(LocalChangeList list) {
      if (myCurrentList == list) {
        return;
      }
      myCurrentList = list;

      if (!myCheckinProjectPanel.hasDiffs()) {
        myPanel.setVisible(false);
        return;
      }

      myPanel.setVisible(true);

      try {
        myVcs.getCheckinData().parameters = new CheckinParameters(myCheckinProjectPanel, true);
        myConfigureButton.setEnabled(true);
        updateMessage(myVcs.getCheckinData());
      }
      catch (OperationFailedException e) {
        myVcs.getCheckinData().parameters = null;
        myConfigureButton.setEnabled(false);
        myVcs.getCheckinData().messageLabel.setIcon(UIUtil.getBalloonErrorIcon());
        myVcs.getCheckinData().messageLabel.setText("Validation failed");
        myVcs.getCheckinData().messageLabel.setToolTipText(e.getMessage());
      }
    }

  }
}
