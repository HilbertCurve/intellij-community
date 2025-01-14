// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager;
import com.intellij.ui.AnActionButton;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * @author Alexander Lobas
 */
public final class SettingsEntryPointAction extends DumbAwareAction implements RightAlignedToolbarAction, TooltipDescriptionProvider {
  private boolean myShowPopup = true;

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    resetActionIcon();

    if (myShowPopup) {
      myShowPopup = false;
      ListPopup popup = createMainPopup(e.getDataContext(), () -> myShowPopup = true);
      PopupUtil.showForActionButtonEvent(popup, e);
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    presentation.setText("");
    presentation.setDescription(getActionTooltip());
    presentation.setIcon(getActionIcon());

    for (AnAction child : getTemplateActions()) {
      child.update(e);
    }
  }

  private static AnAction @NotNull [] getTemplateActions() {
    ActionGroup templateGroup = (ActionGroup)ActionManager.getInstance().getAction("SettingsEntryPointGroup");
    return templateGroup == null ? EMPTY_ARRAY : templateGroup.getChildren(null);
  }

  @NotNull
  private static ListPopup createMainPopup(@NotNull DataContext context, @NotNull Runnable disposeCallback) {
    DefaultActionGroup group = new DefaultActionGroup();

    for (ActionProvider provider : ActionProvider.EP_NAME.getExtensionList()) {
      Collection<AnAction> actions = provider.getUpdateActions(context);
      if (!actions.isEmpty()) {
        for (AnAction action : actions) {
          Presentation presentation = action.getTemplatePresentation();
          if (presentation.getClientProperty(ActionProvider.ICON_KEY) == IconState.ApplicationUpdate) {
            presentation.setIcon(AllIcons.Ide.Notification.IdeUpdate);
          }
          else {
            presentation.setIcon(AllIcons.Ide.Notification.PluginUpdate);
          }
          group.add(action);
        }
        group.addSeparator();
      }
    }

    if (group.getChildrenCount() == 0) {
      resetActionIcon();
    }

    for (AnAction child : getTemplateActions()) {
      if (child instanceof Separator) {
        group.add(child);
      }
      else {
        String text = child.getTemplateText();
        if (text != null && !text.endsWith("...")) {
          AnActionButton button = new AnActionButton.AnActionButtonWrapper(child.getTemplatePresentation(), child) {
            @Override
            public void updateButton(@NotNull AnActionEvent e) {
              getDelegate().update(e);
              e.getPresentation().setText(e.getPresentation().getText() + "...");
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
              super.actionPerformed(new AnActionEvent(e.getInputEvent(), e.getDataContext(), e.getPlace(),
                                                      getDelegate().getTemplatePresentation(), e.getActionManager(), e.getModifiers()));
            }
          };
          button.setShortcut(child.getShortcutSet());
          group.add(button);
        }
        else {
          group.add(child);
        }
      }
    }

    return JBPopupFactory.getInstance()
      .createActionGroupPopup(null, group, context, JBPopupFactory.ActionSelectionAid.MNEMONICS, true, () -> {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
          () -> ApplicationManager.getApplication().invokeLater(disposeCallback, ModalityState.any()), 250, TimeUnit.MILLISECONDS);
      }, -1);
  }

  private static boolean myShowPlatformUpdateIcon;
  private static boolean myShowPluginsUpdateIcon;

  public static void updateState(IconState state) {
    if (state == IconState.ApplicationUpdate) {
      myShowPlatformUpdateIcon = true;
    }
    else if (state == IconState.ApplicationComponentUpdate) {
      myShowPluginsUpdateIcon = true;
    }
    if (isAvailableInStatusBar()) {
      updateWidgets();
    }
  }

  private static @NotNull @Nls String getActionTooltip() {
    return IdeBundle.message("settings.entry.point.tooltip");
  }

  private static void resetActionIcon() {
    myShowPlatformUpdateIcon = myShowPluginsUpdateIcon = false;
  }

  private static @NotNull Icon getActionIcon() {
    if (myShowPlatformUpdateIcon) {
      return AllIcons.Ide.Notification.IdeUpdate;
    }
    if (myShowPluginsUpdateIcon) {
      return AllIcons.Ide.Notification.PluginUpdate;
    }
    return AllIcons.General.GearPlain;
  }

  private static UISettingsListener mySettingsListener;

  private static void initUISettingsListener() {
    if (mySettingsListener == null) {
      mySettingsListener = uiSettings -> updateWidgets();
      ApplicationManager.getApplication().getMessageBus().connect().subscribe(UISettingsListener.TOPIC, mySettingsListener);
    }
  }

  private static void updateWidgets() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      project.getService(StatusBarWidgetsManager.class).updateWidget(StatusBarManager.class);
      IdeFrame frame = WindowManager.getInstance().getIdeFrame(project);
      if (frame != null) {
        StatusBar statusBar = frame.getStatusBar();
        if (statusBar != null) {
          statusBar.updateWidget(WIDGET_ID);
        }
      }
    }
  }

  private static boolean isAvailableInStatusBar() {
    initUISettingsListener();

    UISettings settings = UISettings.getInstance();
    return !settings.getShowMainToolbar() && !settings.getShowToolbarInNavigationBar() && !Registry.is("ide.new.navbar");
  }

  private static final String WIDGET_ID = "settingsEntryPointWidget";

  public static class StatusBarManager implements StatusBarWidgetFactory {
    @Override
    public @NotNull String getId() {
      return WIDGET_ID;
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
      return IdeBundle.message("settings.entry.point.widget.name");
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
      return isAvailableInStatusBar();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
      return new MyStatusBarWidget();
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
      Disposer.dispose(widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
      return isAvailableInStatusBar();
    }

    @Override
    public boolean isConfigurable() {
      return false;
    }
  }

  private static class MyStatusBarWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {
    private StatusBar myStatusBar;
    private boolean myShowPopup = true;

    @Override
    public @NotNull String ID() {
      return WIDGET_ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
      myStatusBar = statusBar;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
      return this;
    }

    @Override
    public @Nullable @NlsContexts.Tooltip String getTooltipText() {
      return getActionTooltip();
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
      return event -> {
        resetActionIcon();
        myStatusBar.updateWidget(WIDGET_ID);

        if (!myShowPopup) {
          return;
        }
        myShowPopup = false;

        Component component = event.getComponent();
        ListPopup popup = createMainPopup(DataManager.getInstance().getDataContext(component), () -> myShowPopup = true);
        popup.addListener(new JBPopupListener() {
          @Override
          public void beforeShown(@NotNull LightweightWindowEvent event) {
            Point location = component.getLocationOnScreen();
            Dimension size = popup.getSize();
            popup.setLocation(new Point(location.x + component.getWidth() - size.width, location.y - size.height));
          }
        });
        popup.show(component);
      };
    }

    @Override
    public @Nullable Icon getIcon() {
      return getActionIcon();
    }

    @Override
    public void dispose() { }
  }

  public enum IconState {
    Current, ApplicationUpdate, ApplicationComponentUpdate
  }

  public interface ActionProvider {
    ExtensionPointName<ActionProvider> EP_NAME = new ExtensionPointName<>("com.intellij.settingsEntryPointActionProvider");

    String ICON_KEY = "Update_Type_Icon_Key";

    @NotNull Collection<AnAction> getUpdateActions(@NotNull DataContext context);
  }
}