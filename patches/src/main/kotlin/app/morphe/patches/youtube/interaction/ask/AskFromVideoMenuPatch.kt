/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.ask

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.FeedFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.FullHistoryFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ask/AskFromVideoMenuPatch;"

private const val EXTENSION_FILTER_CLASS =
    "Lapp/morphe/extension/youtube/patches/components/AskLiveStatusFilter;"

private const val EXTENSION_REGISTRY_CLASS = "Lcom/google/protobuf/ExtensionRegistryLite;"

@Suppress("unused")
val askFromVideoMenuPatch = bytecodePatch(
    name = "Ask from video menu",
    description = "Adds a video menu entry that opens Ask for the video and summarizes the video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        flyoutPatch,
        lithoFilterPatch,
        versionCheckPatch,
        authHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // 20.21 registers engagement panels differently,
        // and 20.51 creates the rows of the video menu differently.
        if (!is_21_15_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Ask from video menu' requires YouTube 21.16+"
            )
        }

        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_ask_summarize_flyout_menu", summary = true)
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Finds the videos of lists that are live or upcoming, before their menu opens.
        addLithoFilter(EXTENSION_FILTER_CLASS)

        // The command executor, which runs a command as if a button sent it.
        val executeCommandMatch = LithoButtonCommandFingerprint.instructionMatches.last()
        val executeCommandMethod = executeCommandMatch.getMethodCalled()
        val commandExecutorClass = executeCommandMethod.definingClass
        val commandClass = executeCommandMethod.parameterTypes.first().toString()

        // GeneratedMessageLite, whose method names are not obfuscated.
        val messageClass = generateSequence(commandClass) { type -> classDefBy(type).superclass }
            .first { type ->
                classDefBy(type).methods.any { method ->
                    method.name == "parseFrom" &&
                            method.parameterTypes.map { it.toString() } ==
                            listOf(type, "[B", EXTENSION_REGISTRY_CLASS)
                }
            }
        val commandDefaultInstanceField = classDefBy(commandClass).fields.first { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == commandClass
        }

        LithoButtonCommandFingerprint.method.apply {
            val executorRegister = getInstruction<FiveRegisterInstruction>(executeCommandMatch.index).registerC
            addInstruction(
                executeCommandMatch.index,
                "invoke-static { v$executorRegister }, $EXTENSION_CLASS->setCommandExecutor(Ljava/lang/Object;)V"
            )
        }

        // The Ask panel of the opened video is registered from the watch page response.
        // The extension keeps it to build the Ask panel of other videos.
        RegisterEngagementPanelFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                invoke-virtual { v0 }, $messageClass->toByteArray()[B
                move-result-object v0
                invoke-static { v0 }, $EXTENSION_CLASS->onEngagementPanelRegistered([B)V
            """
        )

        // When the summarize suggestion is not selected, the welcome request of the Ask panel is replaced
        // with the summarize request, and the playback position is removed from the requests of a panel
        // of another video.
        val formDataField = GetPanelRequestDescriptionFingerprint.let {
            val formDataStringIndex = it.instructionMatches[3].index
            it.method.getInstruction<ReferenceInstruction>(formDataStringIndex - 1).reference as FieldReference
        }
        GetPanelRequestBuilderFingerprint.method.apply {
            val isEmptyIndex = GetPanelRequestBuilderFingerprint.instructionMatches.first().index
            val continuationField = getInstruction<ReferenceInstruction>(isEmptyIndex - 1)
                .reference as FieldReference
            val formDataDefaultInstanceField = classDefBy(formDataField.type).fields.first { field ->
                AccessFlags.STATIC.isSet(field.accessFlags) && field.type == formDataField.type
            }

            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p0, $continuationField
                    invoke-static { v0 }, $EXTENSION_CLASS->onGetPanelRequest(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    iput-object v0, p0, $continuationField
                    iget-object v1, p0, $formDataField
                    if-eqz v1, :form_data_unchanged
                    invoke-virtual { v1 }, $messageClass->toByteArray()[B
                    move-result-object v1
                    invoke-static { v0, v1 }, $EXTENSION_CLASS->getPanelFormData(Ljava/lang/String;[B)[B
                    move-result-object v1
                    if-eqz v1, :form_data_unchanged
                    sget-object v0, $formDataDefaultInstanceField
                    invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
                    move-result-object v2
                    invoke-static { v0, v1, v2 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
                    move-result-object v0
                    check-cast v0, ${formDataField.type}
                    iput-object v0, p0, $formDataField
                    :form_data_unchanged
                    nop
                """
            )
        }

        // The extension runs the tap handler of the summarize suggestion of the opened panel,
        // so the panel shows the suggestion as selected and the answer as loading, as on a tap.
        val tapHandlerMethod = ElementsTapHandlerFingerprint.method
        val tapCommandMethod = ElementsTapHandlerFingerprint.instructionMatches.last().getMethodCalled()
        val tapCommandHolderClass = tapCommandMethod.definingClass
        // The tap handler class is not public, so it is called through its public interface.
        val tapHandlerInterfaceMethod = classDefBy(tapHandlerMethod.definingClass).interfaces.firstNotNullOf { type ->
            classDefBy(type).methods.firstOrNull { method ->
                method.name == tapHandlerMethod.name && method.parameterTypes == tapHandlerMethod.parameterTypes
            }
        }

        mutableClassDefBy(tapHandlerMethod.definingClass).methods.first { method ->
            method.name == "<init>" && method.parameterTypes.any { it.toString() == tapCommandHolderClass }
        }.apply {
            val holderRegister = "p" + (parameterTypes.indexOfFirst { it.toString() == tapCommandHolderClass } + 1)
            val returnIndex = implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            val onCreated = "$EXTENSION_CLASS->onTapHandlerCreated(Ljava/lang/Object;Ljava/lang/Object;)V"
            // The parameters are past v15 if the constructor has many locals, which are free at its return.
            if (implementation!!.registerCount <= 16) {
                addInstruction(returnIndex, "invoke-static { p0, $holderRegister }, $onCreated")
            } else {
                addInstructions(
                    returnIndex,
                    """
                        move-object/from16 v0, p0
                        move-object/from16 v1, $holderRegister
                        invoke-static { v0, v1 }, $onCreated
                    """
                )
            }
        }

        replaceStub("getTapCommand", 2) {
            """
                check-cast p0, $tapCommandHolderClass
                invoke-virtual { p0 }, $tapCommandMethod
                move-result-object v0
                invoke-virtual { v0 }, $messageClass->toByteArray()[B
                move-result-object v0
                return-object v0
            """
        }

        replaceStub("runTapHandler", 2) {
            """
                check-cast p0, ${tapHandlerInterfaceMethod.definingClass}
                const/4 v0, 0x0
                invoke-interface { p0, v0, v0, v0 }, ${tapHandlerInterfaceMethod.toReference()}
                return-void
            """
        }

        addSummarizeItemToMenu(messageClass)?.let { reason ->
            throw PatchException("Could not add the summarize item to the video menu list: $reason")
        }

        addElementsResourceLoading(messageClass)?.let { reason ->
            Logger.getLogger(this::class.java.name).warning(
                "Could not load the Ask chat templates, the Ask panel opened outside a watch page " +
                        "can be empty until a watch page loads: $reason"
            )
        }

        // Parses the command and runs it with the command executor.
        replaceStub("executeCommand", 4) {
            """
                sget-object v0, $commandDefaultInstanceField
                invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
                move-result-object v1
                invoke-static { v0, p1, v1 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
                move-result-object v0
                check-cast v0, $commandClass
                check-cast p0, $commandExecutorClass
                new-instance v1, Ljava/util/HashMap;
                invoke-direct { v1 }, Ljava/util/HashMap;-><init>()V
                invoke-interface { p0, v0, v1 }, $executeCommandMethod
                return-void
            """
        }
    }
}

/**
 * Adds the summarize item to the menu of a video as one of the app's own items, so the menu bottom sheet
 * and the menu popup show it in their own list. The extension updates the row of the item and handles its tap.
 *
 * @return Null if the item is added, or the reason it is not.
 */
private fun BytecodePatchContext.addSummarizeItemToMenu(messageClass: String): String? {
    val menuFragmentClass = MenuBottomSheetFragmentFingerprint.classDefOrNull
        ?: return "menu bottom sheet not found"
    val createRowMethod = menuFragmentClass.methods.singleOrNull { method ->
        method.parameterTypes.size == 1 && method.returnType.endsWith("/util/Optional;")
    } ?: return "row creation method not found"
    val menuItemClass = createRowMethod.parameterTypes.first().toString()
    val tapMethod = menuFragmentClass.methods.firstOrNull { method ->
        method.returnType == "V" && method.parameterTypes.size == 1 &&
                method.implementation?.instructions?.firstOrNull()?.opcode == Opcode.INSTANCE_OF &&
                method.findFieldRead(menuItemClass) != null
    } ?: return "row tap method not found"
    val rowMenuItemField = tapMethod.findFieldRead(menuItemClass)!!
    val menuRowsField = menuFragmentClass.fields.firstOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
                generateSequence(field.type) { type -> classDefByOrNull(type)?.superclass }
                    .any { type -> type == "Ljava/util/AbstractList;" }
    } ?: return "rows field not found"

    // The menu of a video is shown either as a bottom sheet or as a popup, after an optional transform
    // that returns a menu.
    val showMenuMethod = FeedFlyoutBufferObjectFingerprint.method
    val showMenuInstructions = showMenuMethod.implementation!!.instructions.toList()
    val menuClass = showMenuInstructions.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@firstNotNullOfOrNull null
        ((instruction as ReferenceInstruction).reference as MethodReference).takeIf { method ->
            method.parameterTypes.size == 1 && method.parameterTypes.first().toString() == method.returnType &&
                    !method.returnType.startsWith("Ljava/")
        }?.returnType
    } ?: return "menu transform not found"
    val showBottomSheetIndex = showMenuInstructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                ((instruction as ReferenceInstruction).reference as MethodReference).let { method ->
                    method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(menuClass)
                }
    }
    if (showBottomSheetIndex < 0) return "bottom sheet call not found"

    val popupMenu = findPopupMenu(showMenuInstructions, menuClass, menuItemClass, rowMenuItemField.definingClass)

    // Adds the item to the menu before the call that shows the menu, and updates the rows of a popup after it.
    fun MutableMethod.addMenuItemBefore(index: Int, isPopup: Boolean) {
        val (callerRegister, menuRegister) = getInstruction(index).getInvokeRegisters()
        if (isPopup) {
            addInstruction(
                index + 1,
                "invoke-static/range { v$callerRegister .. v$callerRegister }, " +
                        "$EXTENSION_CLASS->onPopupMenuShown(Ljava/lang/Object;)V"
            )
        }
        addInstructions(
            index,
            """
                invoke-static/range { v$menuRegister .. v$menuRegister }, $EXTENSION_CLASS->addMenuItem(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$menuRegister
                check-cast v$menuRegister, $menuClass
            """
        )
    }

    showMenuMethod.apply {
        // The popup is shown before or after the bottom sheet, depending on the app version,
        // so the later call is changed first.
        listOfNotNull(showBottomSheetIndex, popupMenu?.showIndex).sortedDescending().forEach { index ->
            addMenuItemBefore(index, index == popupMenu?.showIndex)
        }
    }

    // Lists that are not built with Litho, such as the History page, show the popup from the menu button
    // with the same call.
    if (popupMenu != null) {
        val showReference =
            ((showMenuInstructions[popupMenu.showIndex] as ReferenceInstruction).reference as MethodReference).toReference()
        val menuButtonMethod = FullHistoryFlyoutBufferObjectFingerprint.method
        val index = menuButtonMethod.implementation!!.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as MethodReference).toReference() == showReference
        }
        if (index >= 0) {
            menuButtonMethod.addMenuItemBefore(index, true)
        } else {
            Logger.getLogger(this::class.java.name).warning(
                "Could not find the popup call of the menu button, " +
                        "menus of lists without Litho, such as the History page, show no summarize item"
            )
        }
    }

    val menus = listOfNotNull(
        MenuRows(menuFragmentClass.type, createRowMethod, menuRowsField),
        popupMenu?.let { MenuRows(it.type, it.createRowMethod, it.rowsField) }
    )
    for (menu in menus) {
        mutableClassDefBy(menu.type).apply {
            // The extension updates the rows of the item through the list of rows.
            fields.first { it.name == menu.rowsField.name }.apply {
                setAccessFlags(
                    accessFlags and (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv() or
                            AccessFlags.PUBLIC.value
                )
            }

            methods.first {
                it.name == menu.createRowMethod.name && it.parameterTypes == menu.createRowMethod.parameterTypes
            }.addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onMenuRowCreated(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $menuItemClass
                """
            )
        }
    }

    // The bottom sheet handles the taps on its rows itself, and the popup with a tap handler of its rows.
    val rowTapMethods = listOfNotNull(tapMethod, popupMenu?.rowTapMethod)
    for (rowTapMethod in rowTapMethods) {
        mutableClassDefBy(rowTapMethod.definingClass).methods.first {
            it.name == rowTapMethod.name && it.parameterTypes == rowTapMethod.parameterTypes
        }.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onMenuRowTapped(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :not_summarize_item
                return-void
                :not_summarize_item
                nop
            """
        )
    }

    // A popup that shows the menu items as they are, such as on tablets, shows them with a presenter
    // that handles their taps.
    val itemTapMethods = popupMenu?.let { findMenuItemTapMethods(it, menuItemClass) }.orEmpty()
    for ((itemTapMethod, itemField) in itemTapMethods) {
        mutableClassDefBy(itemTapMethod.definingClass).methods.first {
            it.name == itemTapMethod.name && it.parameterTypes == itemTapMethod.parameterTypes
        }.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $itemField
                invoke-static/range { v0 .. v0 }, $EXTENSION_CLASS->onMenuItemTapped(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :not_summarize_item
                return-void
                :not_summarize_item
                nop
            """
        )
    }

    replaceStub("canTapMenuItems", 1) {
        """
            const/4 v0, ${if (itemTapMethods.isEmpty()) "0x0" else "0x1"}
            return v0
        """
    }

    replaceStub("serializeMessage", 1) {
        """
            check-cast p0, $messageClass
            invoke-virtual { p0 }, $messageClass->toByteArray()[B
            move-result-object p0
            return-object p0
        """
    }

    for ((stub, type) in listOf("parseMenu" to menuClass, "parseMenuItem" to menuItemClass)) {
        val defaultInstanceField = classDefBy(type).fields.first { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == type
        }
        replaceStub(stub, 3) {
            """
                sget-object v0, $defaultInstanceField
                invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
                move-result-object v1
                invoke-static { v0, p0, v1 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
                move-result-object v0
                return-object v0
            """
        }
    }

    // The menu is any of the menus that create rows, the code of the last one runs if the menu is none of the others.
    fun forEachMenu(smali: (MenuRows) -> String) = menus.withIndex().joinToString("\n") { (index, menu) ->
        val isLast = index == menus.lastIndex
        """
            ${if (isLast) "" else "instance-of v0, p0, ${menu.type}"}
            ${if (isLast) "" else "if-eqz v0, :not_menu_$index"}
            check-cast p0, ${menu.type}
            ${smali(menu)}
            ${if (isLast) "" else ":not_menu_$index"}
        """
    }

    replaceStub("createMenuRow", 4) {
        forEachMenu { menu ->
            """
                check-cast p1, $menuItemClass
                invoke-virtual { p0, p1 }, ${menu.createRowMethod.toReference()}
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual { v0, v1 }, ${menu.createRowMethod.returnType}->orElse(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """
        }
    }

    replaceStub("getMenuRows", 2) {
        forEachMenu { menu ->
            """
                iget-object p0, p0, ${menu.rowsField}
                return-object p0
            """
        }
    }

    replaceStub("dismissPopupMenu", 2) {
        if (popupMenu == null) {
            """
                const/4 v0, 0x0
                return v0
            """
        } else {
            """
                instance-of v0, p0, ${popupMenu.type}
                if-eqz v0, :not_popup_menu
                check-cast p0, ${popupMenu.type}
                invoke-virtual { p0 }, ${popupMenu.dismissMethod.toReference()}
                :not_popup_menu
                return v0
            """
        }
    }

    replaceStub("isMenuItem", 1) {
        """
            instance-of p0, p0, $menuItemClass
            return p0
        """
    }

    replaceStub("getRowMenuItem", 2) {
        """
            instance-of v0, p0, ${rowMenuItemField.definingClass}
            if-eqz v0, :not_menu_item_row
            check-cast p0, ${rowMenuItemField.definingClass}
            iget-object p0, p0, $rowMenuItemField
            return-object p0
            :not_menu_item_row
            const/4 p0, 0x0
            return-object p0
        """
    }

    if (!restoreMenuRowTextConstraints()) {
        Logger.getLogger(this::class.java.name).warning(
            "Could not restore the text constraints of reused menu rows, the summarize item can show its icon over its text"
        )
    }

    return null
}

/**
 * A menu that creates the rows of its menu items, either the bottom sheet or the popup.
 */
private class MenuRows(val type: String, val createRowMethod: Method, val rowsField: FieldReference)

/**
 * The popup that shows the menu of a video, which creates the rows of the menu items
 * and handles the taps on the rows with its own methods.
 */
private class PopupMenu(
    /**
     * The index of the call that shows the popup, in the method that shows the menu.
     */
    val showIndex: Int,
    val type: String,
    val createRowMethod: Method,
    val rowsField: FieldReference,
    val dismissMethod: Method,
    val rowTapMethod: Method,
)

private fun BytecodePatchContext.findPopupMenu(
    showMenuInstructions: List<Instruction>,
    menuClass: String,
    menuItemClass: String,
    rowClass: String,
): PopupMenu? {
    fun notFound(reason: String): PopupMenu? {
        Logger.getLogger(this::class.java.name).warning(
            "Could not add the summarize item to the list of the popup video menu, " +
                    "popup video menus show no summarize item: $reason"
        )
        return null
    }

    // Shows the menu next to the view of its button.
    val showIndex = showMenuInstructions.indexOfFirst { instruction ->
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                ((instruction as ReferenceInstruction).reference as MethodReference).let { method ->
                    method.returnType == "V" &&
                            method.parameterTypes.map { it.toString() }.take(2) == listOf(menuClass, "Landroid/view/View;")
                }
    }
    if (showIndex < 0) return notFound("popup call not found")
    val popupClass = classDefBy(
        ((showMenuInstructions[showIndex] as ReferenceInstruction).reference as MethodReference).definingClass
    )
    if (!AccessFlags.PUBLIC.isSet(popupClass.accessFlags)) return notFound("popup class not public")

    fun Method.isPublicInstanceMethod() =
        AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags) && name != "<init>"

    val createRowMethod = popupClass.methods.singleOrNull { method ->
        method.isPublicInstanceMethod() && method.returnType.endsWith("/util/Optional;") &&
                method.parameterTypes.map { it.toString() } == listOf(menuItemClass)
    } ?: return notFound("popup row creation method not found")
    val rowsField = popupClass.fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
                generateSequence(field.type) { type -> classDefByOrNull(type)?.superclass }
                    .any { type -> type == "Ljava/util/AbstractList;" }
    } ?: return notFound("popup rows field not found")
    val dismissMethod = popupClass.methods.singleOrNull { method ->
        method.isPublicInstanceMethod() && method.returnType == "V" && method.parameterTypes.isEmpty()
    } ?: return notFound("popup dismiss method not found")
    // The popup creates the tap handler of each row with the row.
    val rowTapMethod = createRowMethod.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.NEW_INSTANCE) return@firstNotNullOfOrNull null
        val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
        classDefByOrNull(type)?.methods?.firstOrNull { method ->
            // The hook of the tap needs a register besides the two of the parameters.
            (method.implementation?.registerCount ?: 0) > 2 && method.returnType == "V" &&
                    method.parameterTypes.map { it.toString() } == listOf(rowClass)
        }
    } ?: return notFound("popup row tap handler not found")

    return PopupMenu(showIndex, popupClass.type, createRowMethod, rowsField, dismissMethod, rowTapMethod)
}

/**
 * Finds the presenters that show the menu items of the popup as they are, by their tap methods,
 * which dismiss the popup and run the command of the tapped item.
 *
 * @return The tap methods, each with the field of the menu item that its presenter shows.
 */
private fun BytecodePatchContext.findMenuItemTapMethods(
    popupMenu: PopupMenu,
    menuItemClass: String,
): List<Pair<Method, FieldReference>> {
    val dismissReference = popupMenu.dismissMethod.toReference()
    val tapMethods = mutableListOf<Pair<Method, FieldReference>>()
    classDefForEach { classDef ->
        val tapMethod = classDef.methods.firstOrNull { method ->
            method.name == "onClick" && method.returnType == "V" &&
                    method.parameterTypes.map { it.toString() } == listOf("Landroid/view/View;") &&
                    // The hook of the tap needs a register besides the two of the parameters.
                    (method.implementation?.registerCount ?: 0) > 2
        } ?: return@classDefForEach
        val instructions = tapMethod.implementation!!.instructions.toList()
        val dismissesPopup = instructions.any { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((instruction as ReferenceInstruction).reference as MethodReference).toReference() == dismissReference
        }
        if (!dismissesPopup) return@classDefForEach

        // The presenter keeps the menu item it shows in a field of its own, and casts it when tapped.
        val itemField = instructions.zipWithNext().firstNotNullOfOrNull { (read, cast) ->
            if (read.opcode != Opcode.IGET_OBJECT || cast.opcode != Opcode.CHECK_CAST) return@firstNotNullOfOrNull null
            ((read as ReferenceInstruction).reference as FieldReference).takeIf { field ->
                field.definingClass == classDef.type &&
                        (read as TwoRegisterInstruction).registerA == (cast as OneRegisterInstruction).registerA &&
                        ((cast as ReferenceInstruction).reference as TypeReference).type == menuItemClass
            }
        } ?: return@classDefForEach
        tapMethods.add(tapMethod to itemField)
    }

    if (tapMethods.isEmpty()) {
        Logger.getLogger(this::class.java.name).warning(
            "Could not find the tap handler of the menu items of the popup video menu, " +
                    "popups that show the menu items as they are show the summarize item as a row"
        )
    }
    return tapMethods
}

/**
 * @return The registers of the arguments of an invoke instruction, the first is the caller of a virtual method.
 */
private fun Instruction.getInvokeRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> throw IllegalArgumentException("Not an invoke instruction: $opcode")
}

/**
 * The Ask chat shows nothing until a watch page response attaches its Elements templates.
 * Lets the extension load the Elements resources of a watch page it fetches,
 * with the processor that loads them from the app's own responses.
 *
 * @return Null if the extension can load them, or the reason it cannot.
 */
private fun BytecodePatchContext.addElementsResourceLoading(messageClass: String): String? {
    val loadMethod = ElementsFrameworkUpdatesFingerprint.methodOrNull
        ?: return "framework updates processor not found"
    val processorClass = loadMethod.definingClass
    // The framework updates are a message, unlike the other parameter.
    val frameworkUpdatesClass = loadMethod.parameterTypes.map { it.toString() }.firstOrNull { type ->
        classDefByOrNull(type)?.fields?.any { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == type
        } == true
    } ?: return "framework updates class not found"
    // Takes the account of the response, which the processor does not use for Elements resources.
    val processMethod = classDefBy(processorClass).methods.singleOrNull { method ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map { it.toString() }.let { types ->
                    types.size == 2 && types[1] == frameworkUpdatesClass
                }
    } ?: return "framework updates processing method not found"
    val usedResourcesField = classDefBy(processorClass).fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "Ljava/util/Set;"
    } ?: return "used resources field not found"
    val frameworkUpdatesDefaultInstanceField = classDefBy(frameworkUpdatesClass).fields.first { field ->
        AccessFlags.STATIC.isSet(field.accessFlags) && field.type == frameworkUpdatesClass
    }

    mutableClassDefBy(processorClass).methods.filter { it.name == "<init>" }.forEach { constructor ->
        constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            constructor.addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onElementsResourceProcessorCreated(Ljava/lang/Object;)V"
            )
        }
    }

    replaceStub("parseFrameworkUpdates", 3) {
        """
            sget-object v0, $frameworkUpdatesDefaultInstanceField
            invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
            move-result-object v1
            invoke-static { v0, p0, v1 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
            move-result-object v0
            return-object v0
        """
    }

    replaceStub("processFrameworkUpdates", 3) {
        """
            check-cast p0, $processorClass
            check-cast p1, $frameworkUpdatesClass
            const/4 v0, 0x0
            invoke-virtual { p0, v0, p1 }, ${processMethod.toReference()}
            return-void
        """
    }

    replaceStub("getUsedElementsResources", 1) {
        """
            check-cast p0, $processorClass
            iget-object p0, p0, $processorClass->${usedResourcesField.name}:Ljava/util/Set;
            return-object p0
        """
    }

    return null
}

/**
 * The menu bottom sheet reuses its rows. A row without icons, such as the loading download item,
 * gets start constraints that move its text to the place of the icon, and keeps them when it is reused,
 * such as for the summarize item that replaces its loading item, which then shows its icon over its text.
 * Restores the start constraints of an inflated row before each bind.
 * Newer app versions restore them for rows with icons themselves, some only behind a flag.
 *
 * @return If the constraints are restored.
 */
private fun BytecodePatchContext.restoreMenuRowTextConstraints(): Boolean {
    val match = MenuRowBindFingerprint.matchOrNull() ?: return false
    val getLayoutParamsIndex = match.instructionMatches[1].index
    val textContainerField = match.method.implementation!!.instructions.take(getLayoutParamsIndex)
        .lastOrNull { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                    ((instruction as ReferenceInstruction).reference as FieldReference).type ==
                    "Landroid/widget/LinearLayout;"
        }?.let { (it as ReferenceInstruction).reference as FieldReference } ?: return false
    val constraintsClass = ((match.instructionMatches[3].instruction as ReferenceInstruction).reference
            as TypeReference).type
    val startConstraintFields = match.instructionMatches.takeLast(2).map { it.getFieldAccessed() }

    // -1 is unset, as the layout of the row does not set them.
    match.method.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $textContainerField
            if-eqz v0, :no_text_container
            invoke-virtual { v0 }, Landroid/widget/LinearLayout;->getLayoutParams()Landroid/view/ViewGroup${'$'}LayoutParams;
            move-result-object v1
            check-cast v1, $constraintsClass
            const/4 v2, -0x1
            iput v2, v1, ${startConstraintFields[0]}
            iput v2, v1, ${startConstraintFields[1]}
            invoke-virtual { v0, v1 }, Landroid/widget/LinearLayout;->setLayoutParams(Landroid/view/ViewGroup${'$'}LayoutParams;)V
            :no_text_container
            nop
        """
    )
    return true
}

private fun Method.findFieldRead(type: String) = implementation?.instructions?.firstNotNullOfOrNull { instruction ->
    if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
    ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.type == type }
}

/**
 * Replaces the body of a stub method of the extension class, which can call obfuscated app code.
 */
private fun BytecodePatchContext.replaceStub(name: String, registerCount: Int, smali: () -> String) {
    mutableClassDefBy(EXTENSION_CLASS).apply {
        val stub = methods.first { it.name == name }
        methods.remove(stub)
        methods.add(
            ImmutableMethod(
                type,
                stub.name,
                stub.parameters.map { ImmutableMethodParameter(it.type, null, null) },
                stub.returnType,
                stub.accessFlags,
                null,
                null,
                MutableMethodImplementation(registerCount),
            ).toMutable().apply {
                addInstructionsWithLabels(0, smali())
            }
        )
    }
}

private fun MethodReference.toReference() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
