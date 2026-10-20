/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.ask;

import static app.morphe.extension.shared.StringRef.str;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds a video menu entry that opens the Ask panel of the video and summarizes the video.
 * <p>
 * The Ask panel is the panel of the Ask button under a video. It is built from a saved Ask panel,
 * for the video of the menu, and shown with a command.
 * Then the tap handler of the suggestion that summarizes the video is run, without a tap,
 * so the panel shows the suggestion as selected and the answer as loading, as on a tap.
 * If the panel requests its welcome before the suggestion shows, the welcome request is replaced
 * with the request of the suggestion instead, so the answer shows without the selected suggestion.
 */
@SuppressWarnings("unused")
public final class AskFromVideoMenuPatch {

    /**
     * Wait for the menu to close before showing the panel.
     */
    private static final long SHOW_PANEL_DELAY_MS = 150;

    /**
     * The panel requests the welcome and creates its suggestions as it shows,
     * so a later request or suggestion is not one of the opened panel.
     */
    private static final long OPENED_PANEL_TIMEOUT_MS = 10_000;

    /**
     * Either the summarize suggestion is selected, or the welcome request is replaced, whichever comes first.
     */
    private static final Object pendingLock = new Object();

    private static WeakReference<Object> commandExecutorRef = new WeakReference<>(null);

    /**
     * Loads the Elements resources that responses attach, such as templates.
     */
    private static WeakReference<Object> elementsResourceProcessorRef = new WeakReference<>(null);

    /**
     * The Elements template of the answers of the Ask chat. Only watch page responses attach it.
     */
    private static final String CHAT_RESPONSE_TEMPLATE = "chat_response.eml";

    /**
     * If this class gave the processor the Elements resources of a watch page with an Ask panel,
     * which the processor keeps for the app session. A resource that the processor fails to load without
     * throwing is not loaded again, as the same watch page would likely fail the same way.
     */
    private static volatile boolean loadedChatTemplates;

    /**
     * If the command being executed was built by this class,
     * so the panel it registers is not kept to build other panels.
     */
    private static boolean isExecutingOwnCommand;

    /**
     * The welcome request of the opened panel, which is replaced with the summarize request,
     * unless the summarize suggestion is selected first.
     */
    @Nullable
    private static volatile String pendingWelcomeToken;
    @Nullable
    private static volatile String pendingSummarizeToken;
    private static volatile long pendingTime;

    /**
     * The selection id of the summarize suggestion of the opened panel, whose tap handler is run
     * once it is created, or -1 if no suggestion is awaited.
     */
    private static volatile long pendingSuggestionSelectionId = -1;

    /**
     * The menu bottom sheet or popup that shows the summarize item, to update the item once the live status
     * of its video is known, and to dismiss the popup.
     */
    private static WeakReference<Object> menuRef = new WeakReference<>(null);

    private AskFromVideoMenuPatch() {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * Called before the app executes the command of a tapped button.
     */
    public static void setCommandExecutor(Object commandExecutor) {
        if (commandExecutorRef.get() != commandExecutor) {
            commandExecutorRef = new WeakReference<>(commandExecutor);
        }
    }

    /**
     * Injection point.
     * Called when the app creates the processor of the Elements resources that responses attach.
     */
    public static void onElementsResourceProcessorCreated(Object processor) {
        elementsResourceProcessorRef = new WeakReference<>(processor);
        loadedChatTemplates = false;
    }

    /**
     * Injection point.
     * Called before the app registers an engagement panel.
     */
    public static void onEngagementPanelRegistered(byte[] panel) {
        try {
            if (Settings.ASK_SUMMARIZE_FLYOUT_MENU.get() && !isExecutingOwnCommand
                    && AskPanelCommand.isAskPanel(panel)) {
                AskPanelCommand.saveTemplate(panel);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onEngagementPanelRegistered failure", ex);
        }
    }

    /**
     * Injection point.
     * Called before the app builds a get_panel request, which loads an engagement panel or a turn of its chat.
     *
     * @return The continuation to request.
     */
    public static String onGetPanelRequest(@Nullable String continuation) {
        try {
            final String summarizeToken;
            synchronized (pendingLock) {
                final String welcomeToken = pendingWelcomeToken;
                if (welcomeToken == null || !welcomeToken.equals(continuation)) {
                    return continuation;
                }
                summarizeToken = pendingSummarizeToken;
                pendingWelcomeToken = null;
                pendingSummarizeToken = null;
                // The summarize suggestion has not been selected, and now is not.
                pendingSuggestionSelectionId = -1;
            }
            if (summarizeToken != null
                    && SystemClock.uptimeMillis() - pendingTime < OPENED_PANEL_TIMEOUT_MS) {
                Logger.printDebug(() -> "Requesting the Ask summary instead of the welcome");
                return summarizeToken;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onGetPanelRequest failure", ex);
        }
        return continuation;
    }

    /**
     * Injection point.
     * Called before the app builds a get_panel request.
     *
     * @return The form data to send, or null to send the form data unchanged.
     */
    @Nullable
    public static byte[] getPanelFormData(@Nullable String continuation, byte[] formData) {
        try {
            if (continuation == null) {
                return null;
            }
            // Ask sends the playback position, which is the position of another video
            // if the panel is not the panel of the playing video.
            final String videoId = AskPanelCommand.getVideoIdOfToken(continuation);
            if (videoId == null || videoId.equals(VideoInformation.getVideoId())
                    || videoId.equals(VideoInformation.getPlayerResponseVideoId())) {
                return null;
            }
            byte[] withoutPlayerOffset = AskPanelCommand.removePlayerOffset(formData);
            if (withoutPlayerOffset != null) {
                Logger.printDebug(() -> "Removing the playback position from the Ask request of video: " + videoId);
            }
            return withoutPlayerOffset;
        } catch (Exception ex) {
            Logger.printException(() -> "getPanelFormData failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * Called before the app shows the menu of a video, as a bottom sheet or as a popup.
     *
     * @return The menu with the summarize item, or the menu as is.
     */
    public static Object addMenuItem(Object menu) {
        try {
            if (!isPatchIncluded() || !Settings.ASK_SUMMARIZE_FLYOUT_MENU.get()) {
                return menu;
            }
            final String videoId = FlyoutUtils.getFlyoutVideoId();
            if (videoId.isEmpty()) {
                return menu;
            }

            // Ask cannot summarize a live stream before it ends.
            LiveStatusRequest liveStatus = LiveStatusRequest.fetchRequestIfNeeded(videoId);
            if (liveStatus.isLive()) {
                return menu;
            }

            final boolean isLoading = !liveStatus.fetchIsDone();
            byte[] withItem = AskMenuItem.addToMenu(serializeMessage(menu), videoId,
                    str("morphe_ask_summarize_title"), isLoading);
            if (withItem == null) {
                Logger.printDebug(() -> "Not adding the summarize item to the menu of video: " + videoId);
                return menu;
            }

            if (isLoading) {
                liveStatus.whenDone(isLive -> updateMenuItem(videoId, isLive));
            }
            return parseMenu(withItem);
        } catch (Exception ex) {
            Logger.printException(() -> "addMenuItem failure", ex);
            return menu;
        }
    }

    /**
     * Injection point.
     * Called after the app shows the menu of a video as a popup.
     * The popup can show the menu items as they are, without rows of its own, such as on tablets.
     * The summarize item then shows as text like the other items, with disabled text while it is loading,
     * or as a row of the popup if the patch does not handle the taps on the items as they are.
     */
    public static void onPopupMenuShown(Object popup) {
        try {
            if (!Settings.ASK_SUMMARIZE_FLYOUT_MENU.get()) {
                return;
            }
            List<Object> rows = getMenuRows(popup);
            for (int i = 0, count = rows.size(); i < count; i++) {
                Object row = rows.get(i);
                if (!isMenuItem(row)) {
                    continue;
                }
                byte[] item = serializeMessage(row);
                final String videoId = AskMenuItem.getVideoId(item);
                if (videoId == null) {
                    continue;
                }

                if (canTapMenuItems()) {
                    menuRef = new WeakReference<>(popup);
                    if (AskMenuItem.isLoading(item)) {
                        // The popup shows only the text of an item, and no loading state.
                        // Enabled once the live status is known, as on the other menus.
                        rows.set(i, parseMenuItem(AskMenuItem.build(videoId,
                                str("morphe_ask_summarize_title"), true, true)));
                    }
                    return;
                }
                Object itemRow = createMenuRow(popup, row);
                if (itemRow != null) {
                    rows.set(i, itemRow);
                } else {
                    Logger.printDebug(() -> "Removing the summarize item from a popup without rows");
                    rows.remove(i);
                }
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPopupMenuShown failure", ex);
        }
    }

    /**
     * Injection point.
     * Called when the menu bottom sheet or popup creates the row of a menu item.
     *
     * @return The menu item to show.
     */
    public static Object onMenuRowCreated(Object menu, Object menuItem) {
        try {
            byte[] item = serializeMessage(menuItem);
            final String videoId = AskMenuItem.getVideoId(item);
            if (videoId == null) {
                return menuItem;
            }

            menuRef = new WeakReference<>(menu);
            LiveStatusRequest liveStatus = LiveStatusRequest.fetchRequestIfNeeded(videoId);
            if (AskMenuItem.isLoading(item) && liveStatus.fetchIsDone()) {
                if (liveStatus.isLive()) {
                    // Removed once the menu has its rows.
                    Utils.runOnMainThread(() -> updateMenuItem(videoId, true));
                } else {
                    return parseMenuItem(AskMenuItem.build(videoId, str("morphe_ask_summarize_title"), false));
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuRowCreated failure", ex);
        }
        return menuItem;
    }

    /**
     * Shows the loading summarize item as usual, or removes it if its video is live.
     */
    private static void updateMenuItem(String videoId, boolean isLive) {
        try {
            Object menu = menuRef.get();
            if (menu == null) {
                return;
            }
            List<Object> rows = getMenuRows(menu);
            for (int i = 0, count = rows.size(); i < count; i++) {
                Object row = rows.get(i);
                // A popup can show the menu item as is.
                Object menuItem = isMenuItem(row) ? row : getRowMenuItem(row);
                if (menuItem == null) {
                    continue;
                }
                byte[] item = serializeMessage(menuItem);
                if (!videoId.equals(AskMenuItem.getVideoId(item)) || !AskMenuItem.isLoading(item)) {
                    continue;
                }

                if (isLive) {
                    rows.remove(i);
                } else {
                    Object enabledItem = parseMenuItem(
                            AskMenuItem.build(videoId, str("morphe_ask_summarize_title"), false));
                    Object enabledRow = menuItem == row ? enabledItem : createMenuRow(menu, enabledItem);
                    if (enabledRow != null) {
                        rows.set(i, enabledRow);
                    }
                }
                return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateMenuItem failure", ex);
        }
    }

    /**
     * Injection point.
     * Called when a row of the menu bottom sheet or popup is tapped.
     *
     * @return If the row is the summarize item, which the app then does not handle.
     */
    public static boolean onMenuRowTapped(Object row) {
        return onMenuItemTapped(getRowMenuItem(row));
    }

    /**
     * Injection point.
     * Called when a popup that shows the menu items as they are has one of them tapped,
     * and when a row is tapped, with the menu item of the row.
     *
     * @return If the item is the summarize item, which the app then does not handle.
     */
    public static boolean onMenuItemTapped(@Nullable Object menuItem) {
        try {
            if (menuItem == null || !isMenuItem(menuItem)) {
                return false;
            }
            byte[] item = serializeMessage(menuItem);
            final String videoId = AskMenuItem.getVideoId(item);
            if (videoId == null) {
                return false;
            }
            if (AskMenuItem.isLoading(item)) {
                // Not yet known if the video is live.
                return true;
            }

            if (!dismissPopupMenu(menuRef.get())) {
                FlyoutUtils.dismissFlyout();
            }
            Utils.runOnMainThreadDelayed(() -> openAskPanel(videoId), SHOW_PANEL_DELAY_MS);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuItemTapped failure", ex);
            return false;
        }
    }

    private static byte[] serializeMessage(Object message) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    private static Object parseMenu(byte[] menu) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    private static Object parseMenuItem(byte[] menuItem) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return The row of the menu item, as the menu bottom sheet or popup creates it, or null if it shows no row.
     */
    @Nullable
    private static Object createMenuRow(Object menu, Object menuItem) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return The rows that the menu bottom sheet or popup shows. Changing them updates the menu.
     */
    private static List<Object> getMenuRows(Object menu) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * Dismisses the menu if it is a popup, as the app dismisses it after a tap on one of its rows.
     *
     * @return If the menu is a popup.
     */
    private static boolean dismissPopupMenu(@Nullable Object menu) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return If the row of a menu is a menu item as is, which a popup can show instead of a row of its own.
     */
    private static boolean isMenuItem(Object row) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return If the patch handles the taps on the menu items that a popup shows as they are.
     */
    private static boolean canTapMenuItems() {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return The menu item of a row of the menu bottom sheet or popup, or null if the row has none.
     */
    @Nullable
    private static Object getRowMenuItem(Object row) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * Injection point.
     * Called when a tap handler of an Elements component is created, such as of a suggestion of Ask.
     */
    public static void onTapHandlerCreated(Object tapHandler, Object tapCommand) {
        // A later Ask panel of the app can have a summarize suggestion with the same selection id.
        if (pendingSuggestionSelectionId < 0
                || SystemClock.uptimeMillis() - pendingTime >= OPENED_PANEL_TIMEOUT_MS) {
            return;
        }

        WeakReference<Object> tapHandlerRef = new WeakReference<>(tapHandler);
        WeakReference<Object> tapCommandRef = new WeakReference<>(tapCommand);
        Utils.runOnMainThread(() -> {
            try {
                final long selectionId = pendingSuggestionSelectionId;
                Object handler = tapHandlerRef.get();
                Object command = tapCommandRef.get();
                if (selectionId < 0 || handler == null || command == null
                        || !AskPanelCommand.selectsSuggestion(getTapCommand(command), selectionId)) {
                    return;
                }

                synchronized (pendingLock) {
                    if (pendingSuggestionSelectionId != selectionId) {
                        // The welcome request was replaced first.
                        return;
                    }
                    pendingSuggestionSelectionId = -1;
                    // The suggestion sends its own request, so the welcome is sent as is.
                    pendingWelcomeToken = null;
                    pendingSummarizeToken = null;
                }
                Logger.printDebug(() -> "Selecting the Ask summarize suggestion");
                runTapHandler(handler);
            } catch (Exception ex) {
                Logger.printException(() -> "onTapHandlerCreated failure", ex);
            }
        });
    }

    /**
     * @return The command that a tap handler runs, serialized.
     */
    private static byte[] getTapCommand(Object tapCommand) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * Runs a tap handler as a tap does, without a view or a touch.
     */
    private static void runTapHandler(Object tapHandler) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * Parses the command and executes it with the command executor of the app.
     */
    private static void executeCommand(Object commandExecutor, byte[] command) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    private static Object parseFrameworkUpdates(byte[] frameworkUpdates) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * Loads the Elements resources that the framework updates attach.
     */
    private static void processFrameworkUpdates(Object processor, Object frameworkUpdates) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    /**
     * @return The names of the Elements resources that the app session has used.
     */
    private static Set<?> getUsedElementsResources(Object processor) {
        // Replaced during patching.
        throw new IllegalStateException();
    }

    private static void openAskPanel(String videoId) {
        final boolean needsTemplate = !AskPanelCommand.hasTemplate();
        final boolean needsChatTemplates = !hasChatTemplates();
        if (!needsTemplate && !needsChatTemplates) {
            showAskPanel(videoId);
            return;
        }

        // Without an Ask panel to build from, the watch page of the video is fetched for its Ask panel.
        // The chat shows nothing until a watch page response attaches its Elements templates,
        // so in an app session without them, a watch page with an Ask panel is fetched for them.
        final String watchPageVideoId = needsTemplate ? videoId : AskPanelCommand.getTemplateVideoId();
        Utils.runOnBackgroundThread(() -> {
            byte[] watchPage = AskPanelRequest.fetchWatchPage(watchPageVideoId);
            byte[] panel = watchPage != null
                    ? AskPanelCommand.findAskPanelInWatchPage(watchPage)
                    : null;
            // A watch page without an Ask panel may not attach the templates of the chat,
            // such as when the account can no longer use Ask.
            if (needsChatTemplates && panel != null) {
                loadElementsResources(watchPage);
            }

            Utils.runOnMainThread(() -> {
                if (needsTemplate) {
                    if (panel == null) {
                        Utils.showToastLong(str("morphe_ask_summarize_unavailable"));
                        return;
                    }
                    AskPanelCommand.saveTemplate(panel);
                    if (!AskPanelCommand.hasTemplate()) {
                        Utils.showToastLong(str("morphe_ask_summarize_failed"));
                        return;
                    }
                }
                showAskPanel(videoId);
            });
        });
    }

    /**
     * @return If this class gave the processor the Elements templates of the Ask chat,
     *         if the app session has used them, or if the used Elements resources are not known.
     *         The app marks a template as used when it first shows it, not when a response attaches it,
     *         and it does not always mark the template of the answers as used, such as on 21.42.
     */
    private static boolean hasChatTemplates() {
        if (loadedChatTemplates) {
            return true;
        }
        Object processor = elementsResourceProcessorRef.get();
        if (processor == null) {
            return true;
        }
        Set<?> usedResources = getUsedElementsResources(processor);
        synchronized (usedResources) {
            for (Object resource : usedResources) {
                if (resource instanceof String name && name.startsWith(CHAT_RESPONSE_TEMPLATE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Loads the Elements resources that the response attaches, as the app does with its own responses.
     */
    private static void loadElementsResources(byte[] response) {
        try {
            Object processor = elementsResourceProcessorRef.get();
            byte[] frameworkUpdates = AskPanelCommand.findFrameworkUpdates(response);
            if (processor == null || frameworkUpdates == null) {
                Logger.printDebug(() -> "Not loading Elements resources, processor: " + (processor != null)
                        + " framework updates: " + (frameworkUpdates != null));
                return;
            }

            processFrameworkUpdates(processor, parseFrameworkUpdates(frameworkUpdates));
            loadedChatTemplates = true;
            Logger.printDebug(() -> "Loaded the Elements resources of the response");
        } catch (Exception ex) {
            Logger.printException(() -> "loadElementsResources failure", ex);
        }
    }

    private static void showAskPanel(String videoId) {
        try {
            Object commandExecutor = commandExecutorRef.get();
            if (commandExecutor == null) {
                Logger.printDebug(() -> "No command executor");
                Utils.showToastLong(str("morphe_ask_summarize_failed"));
                return;
            }

            AskPanelCommand.Panel panel = AskPanelCommand.build(videoId);
            if (panel == null) {
                Utils.showToastLong(str("morphe_ask_summarize_failed"));
                return;
            }

            if (panel.welcomeToken == null || panel.summarizeToken == null) {
                Logger.printDebug(() -> "Ask panel has no welcome or summarize request, showing it as is");
            }
            synchronized (pendingLock) {
                pendingWelcomeToken = panel.welcomeToken;
                pendingSummarizeToken = panel.summarizeToken;
                pendingSuggestionSelectionId = panel.summarizeSelectionId;
                pendingTime = SystemClock.uptimeMillis();
            }

            isExecutingOwnCommand = true;
            try {
                executeCommand(commandExecutor, panel.command);
            } finally {
                isExecutingOwnCommand = false;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showAskPanel failure", ex);
            Utils.showToastLong(str("morphe_ask_summarize_failed"));
        }
    }
}
