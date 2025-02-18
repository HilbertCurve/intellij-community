// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins.newui;

import com.intellij.externalDependencies.DependencyOnPlugin;
import com.intellij.externalDependencies.ExternalDependenciesManager;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.plugins.*;
import com.intellij.ide.plugins.marketplace.MarketplaceRequests;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.newEditor.SettingsDialog;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ex.ProgressIndicatorEx;
import com.intellij.openapi.wm.ex.StatusBarEx;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author Alexander Lobas
 */
public class MyPluginModel extends InstalledPluginsTableModel implements PluginManagerMain.PluginEnabler {
  private static final Logger LOG = Logger.getInstance(MyPluginModel.class);

  private final List<ListPluginComponent> myInstalledPluginComponents = new ArrayList<>();
  private final Map<PluginId, List<ListPluginComponent>> myInstalledPluginComponentMap = new HashMap<>();
  private final Map<PluginId, List<ListPluginComponent>> myMarketplacePluginComponentMap = new HashMap<>();
  private final List<PluginsGroup> myEnabledGroups = new ArrayList<>();

  private PluginsGroupComponent myInstalledPanel;
  private PluginsGroup myDownloaded;
  private PluginsGroup myInstalling;
  private Configurable.TopComponentController myTopController;
  private List<String> myVendorsSorted;
  private List<String> myTagsSorted;

  private static final Set<IdeaPluginDescriptor> myInstallingPlugins = new HashSet<>();
  private static final Set<IdeaPluginDescriptor> myInstallingWithUpdatesPlugins = new HashSet<>();
  static final Map<PluginId, InstallPluginInfo> myInstallingInfos = new HashMap<>();

  public boolean needRestart;
  public boolean createShutdownCallback = true;
  private boolean myInstallsRequiringRestart;
  private final List<PluginDetailsPageComponent> myDetailPanels = new ArrayList<>();

  private StatusBarEx myStatusBar;

  private PluginUpdatesService myPluginUpdatesService;

  private Runnable myInvalidFixCallback;
  private Consumer<? super IdeaPluginDescriptor> myCancelInstallCallback;

  private final Map<PluginId, PendingDynamicPluginInstall> myDynamicPluginsToInstall = new LinkedHashMap<>();
  private final Set<IdeaPluginDescriptorImpl> myDynamicPluginsToUninstall = new HashSet<>();
  private final Set<IdeaPluginDescriptorImpl> myPluginsToRemoveOnCancel = new HashSet<>();

  private final Map<IdeaPluginDescriptor, Pair<PluginEnableDisableAction, PluginEnabledState>> myDiff = new HashMap<>();
  private final Map<PluginId, Boolean> myRequiredPluginsForProject = new HashMap<>();
  private final Map<IdeaPluginDescriptorImpl, Boolean> myRequiresRestart = new HashMap<>();
  private final Set<IdeaPluginDescriptor> myUninstalled = new HashSet<>();

  private final Set<PluginId> myErrorPluginsToDisable = new HashSet<>();

  public MyPluginModel(@Nullable Project project) {
    super(project);

    Window window = ProjectUtil.getActiveFrameOrWelcomeScreen();
    myStatusBar = getStatusBar(window);
    if (myStatusBar == null && window != null) {
      myStatusBar = getStatusBar(window.getOwner());
    }
  }

  @Nullable
  private static StatusBarEx getStatusBar(@Nullable Window frame) {
    if (frame instanceof IdeFrame && !(frame instanceof WelcomeFrame)) {
      return (StatusBarEx)((IdeFrame)frame).getStatusBar();
    }
    return null;
  }

  public boolean isModified() {
    return needRestart ||
           !myDynamicPluginsToInstall.isEmpty() ||
           !myDynamicPluginsToUninstall.isEmpty() ||
           !myPluginsToRemoveOnCancel.isEmpty() ||
           !myDiff.isEmpty();
  }

  /**
   * @return true if changes were applied without restart
   */
  public boolean apply(JComponent parent) throws ConfigurationException {
    List<String> dependencies = new ArrayList<>();

    updatePluginDependencies();
    for (Map.Entry<PluginId, Set<PluginId>> entry : getDependentToRequiredListMap().entrySet()) {
      PluginId id = entry.getKey();

      if (!isLoaded(id)) {
        continue;
      }

      for (PluginId dependId : entry.getValue()) {
        if (!PluginManagerCore.isModuleDependency(dependId)) {
          IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(id);
          if (!(descriptor instanceof IdeaPluginDescriptorImpl) ||
              !((IdeaPluginDescriptorImpl)descriptor).isDeleted() && !descriptor.isImplementationDetail()) {
            dependencies.add("\"" + (descriptor == null ? id.getIdString() : descriptor.getName()) + "\"");
          }
          break;
        }
      }
    }

    if (!dependencies.isEmpty()) {
      throw new ConfigurationException(XmlStringUtil.wrapInHtml(
        IdeBundle.message("dialog.message.unable.to.apply.changes", dependencies.size(), StringUtil.join(dependencies, ", "))));
    }

    Set<PluginId> uninstallsRequiringRestart = new HashSet<>();
    for (IdeaPluginDescriptorImpl pluginDescriptor : myDynamicPluginsToUninstall) {
      myDiff.remove(pluginDescriptor);
      PluginId pluginId = pluginDescriptor.getPluginId();

      if (!PluginInstaller.uninstallDynamicPlugin(parent, pluginDescriptor, false)) {
        uninstallsRequiringRestart.add(pluginId);
      }
      else {
        getEnabledMap().remove(pluginId);
      }

      ProjectPluginTrackerManager
        .getInstance()
        .getState()
        .stopTracking(List.of(pluginId));
    }

    boolean installsRequiringRestart = myInstallsRequiringRestart;
    List<PluginId> dynamicPluginsRequiringRestart = new ArrayList<>();

    for (PendingDynamicPluginInstall pendingPluginInstall : myDynamicPluginsToInstall.values()) {
      PluginId pluginId = pendingPluginInstall.getPluginDescriptor().getPluginId();
      if (!uninstallsRequiringRestart.contains(pluginId)) {
        InstalledPluginsState.getInstance().trackPluginInstallation(() -> {
          if (!PluginInstaller.installAndLoadDynamicPlugin(pendingPluginInstall.getFile(),
                                                           parent,
                                                           pendingPluginInstall.getPluginDescriptor())) {
            dynamicPluginsRequiringRestart.add(pluginId);
          }
        });
      }
      else {
        try {
          PluginInstaller.installAfterRestart(pendingPluginInstall.getFile(), !Registry.is("ide.plugins.keep.archive", true),
                                              null, pendingPluginInstall.getPluginDescriptor());
          installsRequiringRestart = true;
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
    }

    myDynamicPluginsToInstall.clear();
    myPluginsToRemoveOnCancel.clear();

    boolean enableDisableAppliedWithoutRestart = applyEnableDisablePlugins(parent);
    myDynamicPluginsToUninstall.clear();
    myDiff.clear();

    boolean changesAppliedWithoutRestart = enableDisableAppliedWithoutRestart &&
                                           uninstallsRequiringRestart.isEmpty() &&
                                           !installsRequiringRestart &&
                                           dynamicPluginsRequiringRestart.isEmpty();
    if (!changesAppliedWithoutRestart) {
      InstalledPluginsState.getInstance().setRestartRequired(true);
    }
    return changesAppliedWithoutRestart;
  }

  public void clear(@Nullable JComponent parentComponent) {
    cancel(parentComponent);

    updateAfterEnableDisable();
  }

  public void cancel(@Nullable JComponent parentComponent) {
    myDiff.forEach((key, value) -> setEnabled(key.getPluginId(), value.getSecond()));
    myDiff.clear();

    myPluginsToRemoveOnCancel.forEach(pluginDescriptor -> PluginInstaller.uninstallDynamicPlugin(parentComponent, pluginDescriptor, false));
    myPluginsToRemoveOnCancel.clear();
  }

  private boolean applyEnableDisablePlugins(@Nullable JComponent parentComponent) {
    EnumMap<PluginEnableDisableAction, List<IdeaPluginDescriptor>> descriptorsByAction = new EnumMap<>(PluginEnableDisableAction.class);

    for (Map.Entry<IdeaPluginDescriptor, Pair<PluginEnableDisableAction, PluginEnabledState>> entry : myDiff.entrySet()) {
      IdeaPluginDescriptor descriptor = entry.getKey();
      PluginId pluginId = descriptor.getPluginId();

      if (descriptor.isImplementationDetail() || /* implementation detail plugins are never explicitly disabled */
          !isLoaded(pluginId) /* if enableMap contains null for id => enable/disable checkbox don't touch */) {
        continue;
      }

      Pair<PluginEnableDisableAction, PluginEnabledState> pair = entry.getValue();
      PluginEnabledState oldState = pair.getSecond();
      PluginEnabledState newState = getState(pluginId);

      if (oldState != newState ||
          newState.isDisabled() && myErrorPluginsToDisable.contains(pluginId)) {
        descriptorsByAction
          .computeIfAbsent(pair.getFirst(), __ -> new ArrayList<>())
          .add(descriptor);
      }
    }

    boolean result = true;
    ProjectPluginTrackerManager pluginTrackerManager = ProjectPluginTrackerManager.getInstance();
    for (Map.Entry<PluginEnableDisableAction, List<IdeaPluginDescriptor>> entry : descriptorsByAction.entrySet()) {
      if (!pluginTrackerManager.updatePluginsState(entry.getValue(), entry.getKey(), getProject(), parentComponent)) {
        result = false;
      }
    }
    return result;
  }

  public void pluginInstalledFromDisk(@NotNull PluginInstallCallbackData callbackData) {
    appendOrUpdateDescriptor(callbackData.getPluginDescriptor(), callbackData.getRestartNeeded());
    if (!callbackData.getRestartNeeded()) {
      myDynamicPluginsToInstall.put(callbackData.getPluginDescriptor().getPluginId(),
                                    new PendingDynamicPluginInstall(callbackData.getFile(), callbackData.getPluginDescriptor()));
    }
  }

  public void addComponent(@NotNull ListPluginComponent component) {
    IdeaPluginDescriptor descriptor = component.getPluginDescriptor();
    PluginId pluginId = descriptor.getPluginId();
    if (!component.isMarketplace()) {
      if (myInstallingPlugins.contains(descriptor) &&
          (myInstalling == null || myInstalling.ui == null || myInstalling.ui.findComponent(pluginId) == null)) {
        return;
      }

      myInstalledPluginComponents.add(component);

      List<ListPluginComponent> components =
        myInstalledPluginComponentMap.computeIfAbsent(pluginId, __ -> new ArrayList<>());
      components.add(component);
    }
    else {
      List<ListPluginComponent> components =
        myMarketplacePluginComponentMap.computeIfAbsent(pluginId, __ -> new ArrayList<>());
      components.add(component);
    }
  }

  public void removeComponent(@NotNull ListPluginComponent component) {
    PluginId pluginId = component.getPluginDescriptor().getPluginId();
    if (!component.isMarketplace()) {
      myInstalledPluginComponents.remove(component);

      List<ListPluginComponent> components = myInstalledPluginComponentMap.get(pluginId);
      if (components != null) {
        components.remove(component);
        if (components.isEmpty()) {
          myInstalledPluginComponentMap.remove(pluginId);
        }
      }
    }
    else {
      List<ListPluginComponent> components = myMarketplacePluginComponentMap.get(pluginId);
      if (components != null) {
        components.remove(component);
        if (components.isEmpty()) {
          myMarketplacePluginComponentMap.remove(pluginId);
        }
      }
    }
  }

  public void setTopController(@NotNull Configurable.TopComponentController topController) {
    myTopController = topController;
    myTopController.showProject(false);

    for (InstallPluginInfo info : myInstallingInfos.values()) {
      info.fromBackground(this);
    }
    if (!myInstallingInfos.isEmpty()) {
      myTopController.showProgress(true);
    }
  }

  public void setPluginUpdatesService(@NotNull PluginUpdatesService service) {
    myPluginUpdatesService = service;
  }

  @Nullable
  public PluginsGroup getDownloadedGroup() {
    return myDownloaded;
  }

  @NotNull
  public static Set<IdeaPluginDescriptor> getInstallingPlugins() {
    return myInstallingPlugins;
  }

  static boolean isInstallingOrUpdate(@NotNull IdeaPluginDescriptor descriptor) {
    return myInstallingWithUpdatesPlugins.contains(descriptor);
  }

  void installOrUpdatePlugin(@Nullable JComponent parentComponent,
                             @NotNull IdeaPluginDescriptor descriptor,
                             @Nullable IdeaPluginDescriptor updateDescriptor,
                             @NotNull ModalityState modalityState) {
    IdeaPluginDescriptor actionDescriptor = updateDescriptor == null ? descriptor : updateDescriptor;
    if (!PluginManagerMain.checkThirdPartyPluginsAllowed(List.of(actionDescriptor))) {
      return;
    }

    boolean allowUninstallWithoutRestart = true;
    if (updateDescriptor != null) {
      IdeaPluginDescriptorImpl installedPluginDescriptor = PluginDescriptorLoader.tryLoadFullDescriptor((IdeaPluginDescriptorImpl) descriptor);
      if (installedPluginDescriptor == null || !DynamicPlugins.allowLoadUnloadWithoutRestart(installedPluginDescriptor)) {
        allowUninstallWithoutRestart = false;
      }
      else if (!installedPluginDescriptor.isEnabled()) {
        try {
          FileUtil.delete(installedPluginDescriptor.getPluginPath());
        }
        catch (IOException e) {
          LOG.debug(e);
        }
      }
      else if (DynamicPlugins.allowLoadUnloadSynchronously(installedPluginDescriptor)) {
        if (!PluginInstaller.uninstallDynamicPlugin(parentComponent, installedPluginDescriptor, true)) {
          allowUninstallWithoutRestart = false;
        }
      }
      else {
        performUninstall(installedPluginDescriptor);
      }
    }

    PluginNode pluginNode;
    if (actionDescriptor instanceof PluginNode) {
      pluginNode = (PluginNode)actionDescriptor;
      if (!pluginNode.detailsLoaded()) {
        Ref<PluginNode> fullNode = new Ref<>();
        if (!ProgressManager.getInstance().runProcessWithProgressSynchronously(
            () -> fullNode.set(MarketplaceRequests.getInstance().loadPluginDetails(((PluginNode) actionDescriptor))),
            IdeBundle.message("progress.title.loading.plugin.details"), true, null, parentComponent)
        ) {
          return;
        }
        pluginNode = fullNode.get();
      }
    }
    else {
      pluginNode = new PluginNode(actionDescriptor.getPluginId(), actionDescriptor.getName(), "-1");
      pluginNode.setDependencies(actionDescriptor.getDependencies());
      pluginNode.setRepositoryName(PluginInstaller.UNKNOWN_HOST_MARKER);
    }
    List<PluginNode> pluginsToInstall = List.of(pluginNode);

    PluginManagerMain.suggestToEnableInstalledDependantPlugins(this, pluginsToInstall);

    installPlugin(pluginsToInstall, prepareToInstall(descriptor, updateDescriptor), allowUninstallWithoutRestart, modalityState);
  }

  private void installPlugin(@NotNull List<PluginNode> pluginsToInstall,
                             @NotNull InstallPluginInfo info,
                             boolean allowInstallWithoutRestart,
                             @NotNull ModalityState modalityState) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      boolean cancel = false;
      boolean error = false;
      boolean showErrors = true;
      boolean restartRequired = true;
      List<PendingDynamicPluginInstall> pluginsToInstallSynchronously = new ArrayList<>();
      try {
        PluginInstallOperation operation = new PluginInstallOperation(pluginsToInstall, getCustomRepoPlugins(), this, info.indicator);
        operation.setAllowInstallWithoutRestart(allowInstallWithoutRestart);
        operation.run();
        for (PendingDynamicPluginInstall install : operation.getPendingDynamicPluginInstalls()) {
          if (DynamicPlugins.allowLoadUnloadSynchronously(install.getPluginDescriptor())) {
            pluginsToInstallSynchronously.add(install);
            myPluginsToRemoveOnCancel.add(install.getPluginDescriptor());
          }
          else {
            myDynamicPluginsToInstall.put(install.getPluginDescriptor().getPluginId(), install);
          }
        }

        error = !operation.isSuccess();
        showErrors = !operation.isShownErrors();
        restartRequired = operation.isRestartRequired();
      }
      catch (ProcessCanceledException e) {
        cancel = true;
      }
      catch (Throwable e) {
        LOG.error(e);
        error = true;
      }

      boolean success = !error;
      boolean _cancel = cancel;
      boolean _showErrors = showErrors;
      boolean finalRestartRequired = restartRequired;
      ApplicationManager.getApplication()
        .invokeLater(() -> {
          boolean dynamicRestartRequired = false;
          for (PendingDynamicPluginInstall install : pluginsToInstallSynchronously) {
            boolean installedWithoutRestart = PluginInstaller.installAndLoadDynamicPlugin(install.getFile(),
                                                                                          myInstalledPanel,
                                                                                          install.getPluginDescriptor());
            if (installedWithoutRestart) {
              IdeaPluginDescriptor installedDescriptor = PluginManagerCore.getPlugin(info.getDescriptor().getPluginId());
              if (installedDescriptor != null) {
                info.setInstalledDescriptor((IdeaPluginDescriptorImpl)installedDescriptor);
              }
            }
            else {
              dynamicRestartRequired = true;
            }
          }
          info.finish(success, _cancel, _showErrors, finalRestartRequired || dynamicRestartRequired);
        }, modalityState);
    });
  }

  public boolean toBackground() {
    for (InstallPluginInfo info : myInstallingInfos.values()) {
      info.toBackground(myStatusBar);
    }
    return !myInstallingInfos.isEmpty();
  }

  @NotNull
  private InstallPluginInfo prepareToInstall(@NotNull IdeaPluginDescriptor descriptor, @Nullable IdeaPluginDescriptor updateDescriptor) {
    boolean install = updateDescriptor == null;
    InstallPluginInfo info = new InstallPluginInfo(descriptor, updateDescriptor, this, install);
    myInstallingInfos.put(descriptor.getPluginId(), info);

    if (myInstallingWithUpdatesPlugins.isEmpty()) {
      myTopController.showProgress(true);
    }
    myInstallingWithUpdatesPlugins.add(descriptor);
    if (install) {
      myInstallingPlugins.add(descriptor);
    }

    if (install && myInstalling != null) {
      if (myInstalling.ui == null) {
        myInstalling.descriptors.add(descriptor);
        myInstalledPanel.addGroup(myInstalling, 0);
      }
      else {
        myInstalledPanel.addToGroup(myInstalling, descriptor);
      }

      myInstalling.titleWithCount();
      myInstalledPanel.doLayout();
    }

    List<ListPluginComponent> gridComponents = myMarketplacePluginComponentMap.get(descriptor.getPluginId());
    if (gridComponents != null) {
      for (ListPluginComponent gridComponent : gridComponents) {
        gridComponent.showProgress();
      }
    }
    List<ListPluginComponent> listComponents = myInstalledPluginComponentMap.get(descriptor.getPluginId());
    if (listComponents != null) {
      for (ListPluginComponent listComponent : listComponents) {
        listComponent.showProgress();
      }
    }
    for (PluginDetailsPageComponent panel : myDetailPanels) {
      if (panel.getPlugin() == descriptor) {
        panel.showProgress();
      }
    }

    return info;
  }

  /**
   * @param descriptor          Descriptor on which the installation was requested (can be a PluginNode or an IdeaPluginDescriptorImpl)
   * @param installedDescriptor If the plugin was loaded synchronously, the descriptor which has actually been installed; otherwise null.
   */
  void finishInstall(@NotNull IdeaPluginDescriptor descriptor,
                     @Nullable IdeaPluginDescriptorImpl installedDescriptor,
                     boolean success,
                     boolean showErrors,
                     boolean restartRequired) {
    InstallPluginInfo info = finishInstall(descriptor);

    if (myInstallingWithUpdatesPlugins.isEmpty()) {
      myTopController.showProgress(false);
    }

    PluginId pluginId = descriptor.getPluginId();
    List<ListPluginComponent> marketplaceComponents = myMarketplacePluginComponentMap.get(pluginId);
    if (marketplaceComponents != null) {
      for (ListPluginComponent gridComponent : marketplaceComponents) {
        if (installedDescriptor != null) {
          gridComponent.setPluginDescriptor(installedDescriptor);
        }
        gridComponent.hideProgress(success, restartRequired);
      }
    }
    List<ListPluginComponent> installedComponents = myInstalledPluginComponentMap.get(pluginId);
    if (installedComponents != null) {
      for (ListPluginComponent listComponent : installedComponents) {
        if (installedDescriptor != null) {
          listComponent.setPluginDescriptor(installedDescriptor);
        }
        listComponent.hideProgress(success, restartRequired);
        listComponent.updateErrors();
      }
    }
    for (PluginDetailsPageComponent panel : myDetailPanels) {
      if (panel.isShowingPlugin(descriptor)) {
        panel.setPlugin(installedDescriptor);
        panel.hideProgress(success);
      }
    }

    if (info.install) {
      if (myInstalling != null && myInstalling.ui != null) {
        clearInstallingProgress(descriptor);
        if (myInstallingPlugins.isEmpty()) {
          myInstalledPanel.removeGroup(myInstalling);
        }
        else {
          myInstalledPanel.removeFromGroup(myInstalling, descriptor);
          myInstalling.titleWithCount();
        }
        myInstalledPanel.doLayout();
      }
      if (success) {
        appendOrUpdateDescriptor(installedDescriptor != null ? installedDescriptor : descriptor, restartRequired);
        appendDependsAfterInstall();
      }
      else if (myCancelInstallCallback != null) {
        myCancelInstallCallback.accept(descriptor);
      }
    }
    else if (success) {
      if (myDownloaded != null && myDownloaded.ui != null && restartRequired) {
        ListPluginComponent component = myDownloaded.ui.findComponent(pluginId);
        if (component != null) {
          component.enableRestart();
        }
      }
    }
    else {
      myPluginUpdatesService.finishUpdate();
    }

    info.indicator.cancel();

    if (success) {
      needRestart = true;
      myInstallsRequiringRestart |= restartRequired;
    }
    if (!success && showErrors) {
      Messages.showErrorDialog(getProject(), IdeBundle.message("plugins.configurable.plugin.installing.failed", descriptor.getName()),
                               IdeBundle.message("action.download.and.install.plugin"));
    }
  }

  private void clearInstallingProgress(@NotNull IdeaPluginDescriptor descriptor) {
    if (myInstallingPlugins.isEmpty()) {
      for (ListPluginComponent listComponent : myInstalling.ui.plugins) {
        listComponent.clearProgress();
      }
    }
    else {
      for (ListPluginComponent listComponent : myInstalling.ui.plugins) {
        if (listComponent.getPluginDescriptor() == descriptor) {
          listComponent.clearProgress();
          return;
        }
      }
    }
  }

  @NotNull
  static InstallPluginInfo finishInstall(@NotNull IdeaPluginDescriptor descriptor) {
    InstallPluginInfo info = myInstallingInfos.remove(descriptor.getPluginId());
    info.close();
    myInstallingWithUpdatesPlugins.remove(descriptor);
    if (info.install) {
      myInstallingPlugins.remove(descriptor);
    }
    return info;
  }

  static void addProgress(@NotNull IdeaPluginDescriptor descriptor, @NotNull ProgressIndicatorEx indicator) {
    myInstallingInfos.get(descriptor.getPluginId()).indicator.addStateDelegate(indicator);
  }

  static void removeProgress(@NotNull IdeaPluginDescriptor descriptor, @NotNull ProgressIndicatorEx indicator) {
    myInstallingInfos.get(descriptor.getPluginId()).indicator.removeStateDelegate(indicator);
  }

  public void addEnabledGroup(@NotNull PluginsGroup group) {
    myEnabledGroups.add(group);
  }

  public void setDownloadedGroup(@NotNull PluginsGroupComponent panel,
                                 @NotNull PluginsGroup downloaded,
                                 @NotNull PluginsGroup installing) {
    myInstalledPanel = panel;
    myDownloaded = downloaded;
    myInstalling = installing;
  }

  private void appendDependsAfterInstall() {
    if (myDownloaded == null || myDownloaded.ui == null) {
      return;
    }

    for (IdeaPluginDescriptor descriptor : InstalledPluginsState.getInstance().getInstalledPlugins()) {
      PluginId pluginId = descriptor.getPluginId();
      if (myDownloaded.ui.findComponent(pluginId) != null) {
        continue;
      }

      appendOrUpdateDescriptor(descriptor, true);

      String id = pluginId.getIdString();

      for (Map.Entry<PluginId, List<ListPluginComponent>> entry : myMarketplacePluginComponentMap.entrySet()) {
        if (id.equals(entry.getKey().getIdString())) {
          for (ListPluginComponent component : entry.getValue()) {
            component.hideProgress(true, true);
          }
          break;
        }
      }
    }
  }

  public void addDetailPanel(@NotNull PluginDetailsPageComponent detailPanel) {
    myDetailPanels.add(detailPanel);
  }

  private void appendOrUpdateDescriptor(@NotNull IdeaPluginDescriptor descriptor) {
    int index = view.indexOf(descriptor);
    if (index < 0) {
      view.add(descriptor);
    }
    else {
      view.set(index, descriptor);
    }
  }

  void appendOrUpdateDescriptor(@NotNull IdeaPluginDescriptor descriptor,
                                boolean restartNeeded) {
    PluginId id = descriptor.getPluginId();
    if (!PluginManagerCore.isPluginInstalled(id)) {
      appendOrUpdateDescriptor(descriptor);
      setEnabled(id, PluginEnabledState.ENABLED);
    }

    if (restartNeeded) {
      needRestart = myInstallsRequiringRestart = true;
    }
    if (myDownloaded == null) {
      return;
    }

    myVendorsSorted = null;
    myTagsSorted = null;

    if (myDownloaded.ui == null) {
      myDownloaded.descriptors.add(descriptor);
      myDownloaded.titleWithEnabled(this);

      myInstalledPanel.addGroup(myDownloaded, myInstalling == null || myInstalling.ui == null ? 0 : 1);
      myInstalledPanel.setSelection(myDownloaded.ui.plugins.get(0));
      myInstalledPanel.doLayout();

      addEnabledGroup(myDownloaded);
    }
    else {
      ListPluginComponent component = myDownloaded.ui.findComponent(id);
      if (component != null) {
        myInstalledPanel.setSelection(component);
        component.enableRestart();
        return;
      }

      myInstalledPanel.addToGroup(myDownloaded, descriptor);
      myDownloaded.titleWithEnabled(this);
      myInstalledPanel.setSelection(myDownloaded.ui.plugins.get(myDownloaded.descriptors.indexOf(descriptor)));
      myInstalledPanel.doLayout();
    }
  }

  @NotNull
  public List<String> getVendors() {
    if (ContainerUtil.isEmpty(myVendorsSorted)) {
      myVendorsSorted = getVendors(getInstalledDescriptors());
    }
    return myVendorsSorted;
  }

  @NotNull
  public List<String> getTags() {
    if (ContainerUtil.isEmpty(myTagsSorted)) {
      Set<String> allTags = new HashSet<>();

      for (IdeaPluginDescriptor descriptor : getInstalledDescriptors()) {
        allTags.addAll(PluginManagerConfigurable.getTags(descriptor));
      }

      myTagsSorted = ContainerUtil.sorted(allTags, String::compareToIgnoreCase);
    }
    return myTagsSorted;
  }

  @NotNull
  public List<IdeaPluginDescriptor> getInstalledDescriptors() {
    assert myInstalledPanel != null;

    return myInstalledPanel
      .getGroups()
      .stream()
      .flatMap(group -> group.plugins.stream())
      .map(ListPluginComponent::getPluginDescriptor)
      .collect(Collectors.toList());
  }

  @NotNull
  public static List<String> getVendors(@NotNull Collection<? extends IdeaPluginDescriptor> descriptors) {
    Map<String, Integer> vendors = new HashMap<>();

    for (IdeaPluginDescriptor descriptor : descriptors) {
      String vendor = StringUtil.trim(descriptor.getVendor());
      if (!StringUtil.isEmptyOrSpaces(vendor)) {
        Integer count = vendors.get(vendor);
        if (count == null) {
          vendors.put(vendor, 1);
        }
        else {
          vendors.put(vendor, count + 1);
        }
      }
    }

    return ContainerUtil.sorted(vendors.keySet(), (v1, v2) -> {
      int result = vendors.get(v2) - vendors.get(v1);
      return result == 0 ? v2.compareToIgnoreCase(v1) : result;
    });
  }

  public static boolean isVendor(@NotNull IdeaPluginDescriptor descriptor, @NotNull Set<String> vendors) {
    String vendor = StringUtil.trim(descriptor.getVendor());
    if (StringUtil.isEmpty(vendor)) {
      return false;
    }

    for (String vendorToFind : vendors) {
      if (vendor.equalsIgnoreCase(vendorToFind) || StringUtil.containsIgnoreCase(vendor, vendorToFind)) {
        return true;
      }
    }

    return false;
  }

  public boolean isEnabled(@NotNull IdeaPluginDescriptor descriptor) {
    return !isDisabled(descriptor.getPluginId());
  }

  public @NotNull PluginEnabledState getState(@NotNull IdeaPluginDescriptor descriptor) {
    return getState(descriptor.getPluginId());
  }

  @NotNull ProjectDependentPluginEnabledState getProjectDependentState(@NotNull IdeaPluginDescriptor descriptor) {
    PluginId pluginId = descriptor.getPluginId();
    return new ProjectDependentPluginEnabledState(
      pluginId,
      getState(pluginId),
      getProject()
    );
  }

  /**
   * @see #isEnabled(PluginId, Map)
   */
  @NotNull PluginEnabledState getState(@NotNull PluginId pluginId) {
    PluginEnabledState state = getEnabledMap().get(pluginId);
    return state != null ? state : PluginEnabledState.ENABLED;
  }

  boolean isRequiredPluginForProject(@NotNull PluginId pluginId) {
    Project project = getProject();
    return project != null &&
           myRequiredPluginsForProject.computeIfAbsent(
             pluginId,
             id -> ContainerUtil.exists(
               getDependenciesOnPlugins(project),
               id.getIdString()::equals
             )
           );
  }

  boolean requiresRestart(@NotNull IdeaPluginDescriptor descriptor) {
    return myRequiresRestart.computeIfAbsent(
      descriptor instanceof IdeaPluginDescriptorImpl ? (IdeaPluginDescriptorImpl)descriptor : null,
      descriptorImpl -> {
        IdeaPluginDescriptorImpl fullDescriptor = descriptorImpl == null ?
                                                  null :
                                                  PluginDescriptorLoader.tryLoadFullDescriptor(descriptorImpl);

        return fullDescriptor == null ||
               DynamicPlugins.checkCanUnloadWithoutRestart(fullDescriptor) != null;
      }
    );
  }

  boolean isUninstalled(@NotNull IdeaPluginDescriptor descriptor) {
    return myUninstalled.contains(descriptor);
  }

  void addUninstalled(@NotNull IdeaPluginDescriptor descriptor) {
    myUninstalled.add(descriptor);
  }

  public void changeEnableDisable(@NotNull Set<? extends IdeaPluginDescriptor> plugins,
                                  @NotNull PluginEnableDisableAction action) {
    enableRows(plugins, action);
    updateAfterEnableDisable();
    runInvalidFixCallback();
  }

  @Override
  public void enablePlugins(@NotNull Set<? extends IdeaPluginDescriptor> plugins) {
    changeEnableDisable(plugins, PluginEnableDisableAction.ENABLE_GLOBALLY);
  }

  @Override
  public void disablePlugins(@NotNull Set<? extends IdeaPluginDescriptor> plugins) {
    changeEnableDisable(plugins, PluginEnableDisableAction.DISABLE_GLOBALLY);
  }

  void enableRequiredPlugins(@NotNull IdeaPluginDescriptor descriptor) {
    Set<PluginId> requiredPluginIds = getRequiredPlugins(descriptor.getPluginId());
    if (ContainerUtil.isEmpty(requiredPluginIds)) {
      return;
    }

    List<IdeaPluginDescriptor> allPlugins = getAllPlugins();
    Set<IdeaPluginDescriptor> requiredPlugins = new HashSet<>();

    for (PluginId pluginId : requiredPluginIds) {
      IdeaPluginDescriptor result = ContainerUtil.find(allPlugins, d -> pluginId.equals(d.getPluginId()));
      if (result == null && PluginManagerCore.isModuleDependency(pluginId)) {
        result = ContainerUtil.find(
          allPlugins,
          d -> d instanceof IdeaPluginDescriptorImpl && ((IdeaPluginDescriptorImpl)d).getModules().contains(pluginId)
        );
        if (result != null) {
          setEnabled(pluginId, PluginEnabledState.ENABLED); // todo
        }
      }
      if (result != null) {
        requiredPlugins.add(result);
      }
    }

    if (!requiredPlugins.isEmpty()) {
      enablePlugins(requiredPlugins);
    }
  }

  @Override
  protected void handleBeforeChangeEnableState(@NotNull IdeaPluginDescriptor descriptor,
                                               @NotNull Pair<PluginEnableDisableAction, PluginEnabledState> pair) {
    PluginId pluginId = descriptor.getPluginId();
    Pair<PluginEnableDisableAction, PluginEnabledState> oldPair = myDiff.get(descriptor);

    PluginEnabledState oldState = oldPair != null ? oldPair.getSecond() : null;
    PluginEnabledState newState = pair.getSecond();
    if (oldState != newState) {
      PluginEnabledState state = oldState != null ? oldState : getState(pluginId);
      myDiff.put(
        descriptor,
        Pair.create(pair.getFirst(), state)
      );
    }
    else {
      myDiff.remove(descriptor);
    }

    myErrorPluginsToDisable.remove(pluginId);

    if (newState.isEnabled() ||
        descriptor.isEnabled()) {
      return;
    }

    if (PluginManagerCore.isIncompatible(descriptor) ||
        PluginManagerCore.isBrokenPlugin(descriptor) ||
        hasProblematicDependencies(pluginId)) {
      myErrorPluginsToDisable.add(pluginId);
    }
  }

  private void runInvalidFixCallback() {
    if (myInvalidFixCallback != null) {
      ApplicationManager.getApplication().invokeLater(myInvalidFixCallback, ModalityState.any());
    }
  }

  public void setInvalidFixCallback(@Nullable Runnable invalidFixCallback) {
    myInvalidFixCallback = invalidFixCallback;
  }

  public void setCancelInstallCallback(@NotNull Consumer<? super IdeaPluginDescriptor> callback) {
    myCancelInstallCallback = callback;
  }

  private void updateAfterEnableDisable() {
    for (ListPluginComponent component : myInstalledPluginComponents) {
      component.updateEnabledState();
    }
    for (PluginDetailsPageComponent detailPanel : myDetailPanels) {
      detailPanel.updateEnabledState();
    }
    for (PluginsGroup group : myEnabledGroups) {
      group.titleWithEnabled(this);
    }
  }

  public void runRestartButton(@NotNull Component component) {
    if (PluginManagerConfigurable.showRestartDialog() == Messages.YES) {
      needRestart = true;
      createShutdownCallback = false;

      DialogWrapper settings = DialogWrapper.findInstance(component);
      if (settings instanceof SettingsDialog) {
        ((SettingsDialog)settings).applyAndClose(false /* will be saved on app exit */);
      }
      else if (isModified()) {
        try {
          apply(null);
        }
        catch (ConfigurationException e) {
          LOG.error(e);
        }
      }
      ApplicationManager.getApplication().exit(true, false, true);
    }
  }

  static boolean showUninstallDialog(@NotNull Component uiParent, @Nullable String singleName, int count) {
    String message;
    if (singleName == null) {
      message = IdeBundle.message("prompt.uninstall.several.plugins", count);
    }
    else {
      message = IdeBundle.message("prompt.uninstall.plugin", singleName);
    }
    return MessageDialogBuilder.yesNo(IdeBundle.message("title.plugin.uninstall"), message).ask(uiParent);
  }

  void uninstallAndUpdateUi(@NotNull Component uiParent, @NotNull IdeaPluginDescriptor descriptor) {
    List<IdeaPluginDescriptor> deps = dependent(descriptor);
    if (!deps.isEmpty()) {
      String listOfDeps = StringUtil.join(deps, plugin -> {
        return "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;" + plugin.getName();
      }, "<br>");
      String message = XmlStringUtil
        .wrapInHtml(IdeBundle.message("dialog.message.following.plugin.depend.on", deps.size(), descriptor.getName(), listOfDeps));
      String title = IdeBundle.message("title.plugin.uninstall");
      if (!MessageDialogBuilder.yesNo(title, message).ask(uiParent)) {
        return;
      }
    }

    boolean needRestartForUninstall = performUninstall((IdeaPluginDescriptorImpl)descriptor);
    needRestart |= descriptor.isEnabled() && needRestartForUninstall;
    myInstallsRequiringRestart |= needRestartForUninstall;

    List<ListPluginComponent> listComponents = myInstalledPluginComponentMap.get(descriptor.getPluginId());
    if (listComponents != null) {
      for (ListPluginComponent listComponent : listComponents) {
        listComponent.updateAfterUninstall(needRestartForUninstall);
      }
    }

    for (ListPluginComponent component : myInstalledPluginComponents) {
      component.updateErrors();
    }

    for (PluginDetailsPageComponent panel : myDetailPanels) {
      if (panel.getPlugin() == descriptor) {
        panel.updateButtons();
      }
    }
  }

  private boolean performUninstall(@NotNull IdeaPluginDescriptorImpl descriptorImpl) {
    boolean needRestartForUninstall = true;
    try {
      descriptorImpl.setDeleted(true);
      // Load descriptor to make sure we get back all the data cleared after the descriptor has been loaded
      IdeaPluginDescriptorImpl fullDescriptor = PluginDescriptorLoader.tryLoadFullDescriptor(descriptorImpl);
      LOG.assertTrue(fullDescriptor != null);
      needRestartForUninstall = PluginInstaller.prepareToUninstall(fullDescriptor);
      InstalledPluginsState.getInstance().onPluginUninstall(descriptorImpl, needRestartForUninstall);
      if (!needRestartForUninstall) {
        myDynamicPluginsToUninstall.add(fullDescriptor);
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
    return needRestartForUninstall;
  }

  @Nullable
  public static IdeaPluginDescriptor findPlugin(@NotNull PluginId id) {
    IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(id);
    if (plugin == null && PluginManagerCore.isModuleDependency(id)) {
      IdeaPluginDescriptor descriptor = PluginManagerCore.findPluginByModuleDependency(id);
      if (descriptor != null) return descriptor;
    }
    return plugin;
  }

  public boolean hasProblematicDependencies(PluginId pluginId) {
    Set<PluginId> ids = getDependentToRequiredListMap().get(pluginId);
    if (ContainerUtil.isEmpty(ids)) {
      return false;
    }

    for (PluginId id : ids) {
      IdeaPluginDescriptor plugin = findPlugin(id);
      if (plugin != null && !isEnabled(plugin)) {
        return true;
      }
    }

    return false;
  }

  public boolean hasErrors(@NotNull IdeaPluginDescriptor plugin) {
    return getErrorMessage(plugin, null) != null;
  }

  @Nullable
  public @Nls String getErrorMessage(@NotNull IdeaPluginDescriptor pluginDescriptor, @Nullable Ref<? super @Nls String> enableAction) {
    if (pluginDescriptor instanceof IdeaPluginDescriptorImpl && ((IdeaPluginDescriptorImpl)pluginDescriptor).isDeleted()) {
      return null;
    }
    if (InstalledPluginsState.getInstance().wasUninstalledWithoutRestart(pluginDescriptor.getPluginId())) {
      return null;
    }
    if (InstalledPluginsState.getInstance().wasInstalledWithoutRestart(pluginDescriptor.getPluginId())) {
      // we'll actually install the plugin when the configurable is closed; at this time we don't know if there's any error
      return null;
    }

    String message = PluginManagerCore.getShortLoadingErrorMessage(pluginDescriptor);

    PluginId disabledDependency = PluginManagerCore.getFirstDisabledDependency(pluginDescriptor);
    if (disabledDependency != null) {
      Set<PluginId> requiredPlugins = filterRequiredPlugins(getRequiredPlugins(pluginDescriptor.getPluginId()));
      if (!ContainerUtil.isEmpty(requiredPlugins)) {
        boolean[] enable = {true};
        String deps = StringUtil.join(requiredPlugins, id -> {
          IdeaPluginDescriptor plugin = findPlugin(id);
          if (enable[0] && (plugin == null || PluginManagerCore.isIncompatible(plugin))) {
            enable[0] = false;
          }
          return StringUtil.wrapWithDoubleQuote(plugin != null ? plugin.getName() : id.getIdString());
        }, ", ");

        int size = requiredPlugins.size();
        message = IdeBundle.message("new.plugin.manager.incompatible.deps.tooltip", size, deps);
        if (enable[0] && enableAction != null) {
          enableAction.set(IdeBundle.message("new.plugin.manager.incompatible.deps.action", size));
        }
      }
      else {
        message = null;
      }
    }

    return message;
  }

  @Nullable
  private static Set<PluginId> filterRequiredPlugins(@Nullable Set<PluginId> requiredPlugins) {
    if (ContainerUtil.isEmpty(requiredPlugins)) {
      return requiredPlugins;
    }
    return requiredPlugins.stream().filter(id -> {
      IdeaPluginDescriptor plugin = findPlugin(id);
      return plugin == null || !plugin.isEnabled() ;
    }).collect(Collectors.toSet());
  }

  @NotNull
  protected Collection<IdeaPluginDescriptor> getCustomRepoPlugins() {
    return CustomPluginRepositoryService.getInstance().getCustomRepositoryPlugins();
  }

  @NotNull
  private List<IdeaPluginDescriptor> dependent(@NotNull IdeaPluginDescriptor rootDescriptor) {
    ApplicationInfoEx appInfo = ApplicationInfoEx.getInstanceEx();
    PluginId rootId = rootDescriptor.getPluginId();

    List<IdeaPluginDescriptor> result = new ArrayList<>();
    for (IdeaPluginDescriptor plugin : getAllPlugins()) {
      PluginId pluginId = plugin.getPluginId();
      if (pluginId == rootId || appInfo.isEssentialPlugin(pluginId) || !plugin.isEnabled() || plugin.isImplementationDetail()) {
        continue;
      }
      if (!(plugin instanceof IdeaPluginDescriptorImpl) || ((IdeaPluginDescriptorImpl)plugin).isDeleted()) {
        continue;
      }

      PluginManagerCore.processAllDependencies((IdeaPluginDescriptorImpl)plugin, false, PluginManagerCore.buildPluginIdMap(), (id, descriptor) -> {
        if (id == rootId) {
          result.add(plugin);
          return FileVisitResult.TERMINATE;
        }
        return FileVisitResult.CONTINUE;
      });
    }
    return result;
  }

  private final Map<String, Icon> myIcons = new HashMap<>(); // local cache for PluginLogo WeakValueMap

  @NotNull
  public Icon getIcon(@NotNull IdeaPluginDescriptor descriptor, boolean big, boolean error, boolean disabled) {
    String key = descriptor.getPluginId().getIdString() + big + error + disabled;
    Icon icon = myIcons.get(key);
    if (icon == null) {
      icon = PluginLogo.getIcon(descriptor, big, error, disabled);
      if (icon != PluginLogo.getDefault().getIcon(big, error, disabled)) {
        myIcons.put(key, icon);
      }
    }
    return icon;
  }

  private static @NotNull List<String> getDependenciesOnPlugins(@NotNull Project project) {
    return ContainerUtil.map(
      ExternalDependenciesManager.getInstance(project).getDependencies(DependencyOnPlugin.class),
      DependencyOnPlugin::getPluginId
    );
  }
}