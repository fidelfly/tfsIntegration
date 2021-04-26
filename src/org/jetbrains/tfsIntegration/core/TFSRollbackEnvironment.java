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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.rollback.DefaultRollbackEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vfs.VirtualFile;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.GetOperation;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.RecursionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.tfsIntegration.core.tfs.*;
import org.jetbrains.tfsIntegration.core.tfs.operations.ApplyGetOperations;
import org.jetbrains.tfsIntegration.core.tfs.operations.ApplyProgress;
import org.jetbrains.tfsIntegration.core.tfs.operations.UndoPendingChanges;
import org.jetbrains.tfsIntegration.core.tfs.version.ChangesetVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.WorkspaceVersionSpec;
import org.jetbrains.tfsIntegration.exceptions.TfsException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TFSRollbackEnvironment extends DefaultRollbackEnvironment {

  private final @NotNull Project myProject;

  public TFSRollbackEnvironment(final Project project) {
    myProject = project;
  }

  @Override
  @SuppressWarnings({"ConstantConditions"})
  public void rollbackChanges(final List<? extends Change> changes,
                              final List<VcsException> vcsExceptions,
                              @NotNull final RollbackProgressListener listener) {
    List<FilePath> localPaths = new ArrayList<>();

    listener.determinate();
    for (Change change : changes) {
      ContentRevision revision = change.getType() == Change.Type.DELETED ? change.getBeforeRevision() : change.getAfterRevision();
      localPaths.add(revision.getFile());
    }
    undoPendingChanges(localPaths, vcsExceptions, listener, false);
  }

  @Override
  public void rollbackMissingFileDeletion(final List<? extends FilePath> files,
                                          final List<? super VcsException> errors,
                                          final RollbackProgressListener listener) {
    try {
      WorkstationHelper.processByWorkspaces(files, false, myProject, new WorkstationHelper.VoidProcessDelegate() {
        @Override
        public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) throws TfsException {
          final List<VersionControlServer.GetRequestParams> download = new ArrayList<>();
          final Collection<String> undo = new ArrayList<>();
          StatusProvider.visitByStatus(workspace, paths, false, null, new StatusVisitor() {

            @Override
            public void unversioned(final @NotNull FilePath localPath,
                                    final boolean localItemExists,
                                    final @NotNull ServerStatus serverStatus) throws TfsException {
              TFSVcs.error("Server returned status Unversioned when rolling back missing file deletion: " + localPath.getPresentableUrl());
            }

            @Override
            public void checkedOutForEdit(final @NotNull FilePath localPath,
                                          final boolean localItemExists,
                                          final @NotNull ServerStatus serverStatus) {
              undo.add(serverStatus.targetItem);
            }

            @Override
            public void scheduledForAddition(final @NotNull FilePath localPath,
                                             final boolean localItemExists,
                                             final @NotNull ServerStatus serverStatus) {
              undo.add(serverStatus.targetItem);
            }

            @Override
            public void scheduledForDeletion(final @NotNull FilePath localPath,
                                             final boolean localItemExists,
                                             final @NotNull ServerStatus serverStatus) {
              TFSVcs.error(
                "Server returned status ScheduledForDeletion when rolling back missing file deletion: " + localPath.getPresentableUrl());
            }

            @Override
            public void outOfDate(final @NotNull FilePath localPath,
                                  final boolean localItemExists,
                                  final @NotNull ServerStatus serverStatus) throws TfsException {
              addForDownload(serverStatus);
            }

            @Override
            public void deleted(final @NotNull FilePath localPath,
                                final boolean localItemExists,
                                final @NotNull ServerStatus serverStatus) {
              TFSVcs.error("Server returned status Deleted when rolling back missing file deletion: " + localPath.getPath());
            }

            @Override
            public void upToDate(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
              throws TfsException {
              addForDownload(serverStatus);
            }

            @Override
            public void renamed(final @NotNull FilePath localPath, final boolean localItemExists, final @NotNull ServerStatus serverStatus)
              throws TfsException {
              undo.add(serverStatus.targetItem);
            }

            @Override
            public void renamedCheckedOut(final @NotNull FilePath localPath,
                                          final boolean localItemExists,
                                          final @NotNull ServerStatus serverStatus) throws TfsException {
              undo.add(serverStatus.targetItem);
            }

            @Override
            public void undeleted(final @NotNull FilePath localPath,
                                  final boolean localItemExists,
                                  final @NotNull ServerStatus serverStatus) throws TfsException {
              addForDownload(serverStatus);
            }

            private void addForDownload(final @NotNull ServerStatus serverStatus) {
              download.add(new VersionControlServer.GetRequestParams(serverStatus.targetItem, RecursionType.None,
                                                                     new ChangesetVersionSpec(serverStatus.localVer)));
            }


          }, myProject);

          List<GetOperation> operations = workspace.getServer().getVCS()
            .get(workspace.getName(), workspace.getOwnerName(), download, myProject, TFSBundle.message("preparing.for.download"));
          final Collection<VcsException> downloadErrors =
            ApplyGetOperations.execute(myProject, workspace, operations, ApplyProgress.EMPTY, null, ApplyGetOperations.DownloadMode.FORCE);
          errors.addAll(downloadErrors);

          final UndoPendingChanges.UndoPendingChangesResult undoResult =
            UndoPendingChanges.execute(myProject, workspace, undo, false, new ApplyProgress.RollbackProgressWrapper(listener), false);
          errors.addAll(undoResult.errors);
        }
      });
    }
    catch (TfsException e) {
      errors.add(new VcsException(e.getMessage(), e));
    }
  }

  @Override
  public void rollbackModifiedWithoutCheckout(final List<? extends VirtualFile> files,
                                              final List<? super VcsException> errors,
                                              final RollbackProgressListener listener) {
    try {
      WorkstationHelper.processByWorkspaces(TfsFileUtil.getFilePaths(files), false, myProject, new WorkstationHelper.VoidProcessDelegate() {
        @Override
        public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) throws TfsException {
          // query extended items to determine base (local) version
          //Map<ItemPath, ExtendedItem> extendedItems = workspace.getExtendedItems(paths);

          // query GetOperation-s
          List<VersionControlServer.GetRequestParams> requests = new ArrayList<>(paths.size());
          final WorkspaceVersionSpec versionSpec = new WorkspaceVersionSpec(workspace.getName(), workspace.getOwnerName());
          for (ItemPath e : paths) {
            requests.add(new VersionControlServer.GetRequestParams(e.getServerPath(), RecursionType.None, versionSpec));
          }
          List<GetOperation> operations = workspace.getServer().getVCS()
            .get(workspace.getName(), workspace.getOwnerName(), requests, myProject, TFSBundle.message("preparing.for.download"));
          final Collection<VcsException> applyingErrors = ApplyGetOperations
            .execute(myProject, workspace, operations, new ApplyProgress.RollbackProgressWrapper(listener), null,
                     ApplyGetOperations.DownloadMode.FORCE);
          errors.addAll(applyingErrors);
        }
      });
    }
    catch (TfsException e) {
      errors.add(new VcsException("Cannot undo pending changes", e));
    }
  }

  private void undoPendingChanges(final List<FilePath> localPaths,
                                  final List<VcsException> errors,
                                  @NotNull final RollbackProgressListener listener,
                                  final boolean tolerateNoChangesFailure) {
    try {
      WorkstationHelper.processByWorkspaces(localPaths, false, myProject, new WorkstationHelper.VoidProcessDelegate() {
        @Override
        public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) throws TfsException {
          Collection<String> serverPaths = new ArrayList<>(paths.size());
          for (ItemPath itemPath : paths) {
            serverPaths.add(itemPath.getServerPath());
          }
          UndoPendingChanges.UndoPendingChangesResult undoResult = UndoPendingChanges
            .execute(myProject, workspace, serverPaths, false, new ApplyProgress.RollbackProgressWrapper(listener),
                     tolerateNoChangesFailure);
          errors.addAll(undoResult.errors);
          List<VirtualFile> refresh = new ArrayList<>(paths.size());
          for (ItemPath path : paths) {
            listener.accept(path.getLocalPath());

            ItemPath undone = undoResult.undonePaths.get(path);
            FilePath subject = (undone != null ? undone : path).getLocalPath();
            VirtualFile file = subject.getVirtualFileParent();
            if (file != null && file.exists()) {
              refresh.add(file);
            }
          }
          TfsFileUtil.refreshAndMarkDirty(myProject, refresh, true);
        }
      });
    }
    catch (TfsException e) {
      errors.add(new VcsException("Cannot undo pending changes", e));
    }
  }


}
