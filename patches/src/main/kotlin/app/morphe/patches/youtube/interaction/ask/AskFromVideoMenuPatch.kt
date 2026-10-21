/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.Match
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.FeedFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.FullHistoryFlyoutBufferObjectFingerprint
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.registersUsed
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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
    description = "Adds a video menu entry that opens Ask for the video and summarizes the video. " +
            "This patch works with YouTube 21.16 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        flyoutPatch,
        lithoFilterPatch,
        versionCheckPatch,
        authHookPatch,
        playerFlyoutPreferences
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

        addPlayerFlyoutMenuPreferences(
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
        val protos = Protos(this, messageClass)

        LithoButtonCommandFingerprint.method.apply {
            val executorRegister = getInstruction<FiveRegisterInstruction>(executeCommandMatch.index).registerC
            addInstruction(
                executeCommandMatch.index,
                "invoke-static { v$executorRegister }, $EXTENSION_CLASS->setCommandExecutor(Ljava/lang/Object;)V"
            )
        }

        // The Ask panel of the opened video is registered from the watch page response.
        // The extension keeps it to build the Ask panel of other videos.
        RegisterEngagementPanelFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onEngagementPanelRegistered(Ljava/lang/Object;)V"
        )

        // When the summarize suggestion is not selected, the welcome request of the Ask panel is replaced
        // with the summarize request, and the playback position is removed from the requests of a panel
        // of another video.
        val continuationField = GetPanelRequestDescriptionFingerprint.instructionMatches[3].getFieldReference()
        val formDataField = GetPanelRequestDescriptionFingerprint.instructionMatches[4].getFieldReference()
        GetPanelRequestBuilderFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p0, $continuationField
                    invoke-static { v0 }, $EXTENSION_CLASS->onGetPanelRequest(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    iput-object v0, p0, $continuationField
                    iget-object v1, p0, $formDataField
                    invoke-static { v0, v1 }, $EXTENSION_CLASS->getPanelFormData(Ljava/lang/String;Ljava/lang/Object;)[B
                    move-result-object v1
                    if-eqz v1, :form_data_unchanged
                    ${protos.parse(formDataField.type, bytes = "v1", result = "v0", registry = "v2")}
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
        // The tap handler class is not public, so it is called through the interface it implements.
        val tapHandlerInterface = classDefBy(tapHandlerMethod.definingClass).interfaces.single()

        ElementsTapHandlerConstructorFingerprint.method.apply {
            val holderRegister = "p" + (parameterTypes.indexOfFirst { it.toString() == tapCommandHolderClass } + 1)
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
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
                check-cast p0, $tapHandlerInterface
                const/4 v0, 0x0
                invoke-interface { p0, v0, v0, v0 }, $tapHandlerInterface->${tapHandlerMethod.name}(${tapHandlerMethod.parameterTypes.joinToString("")})V
                return-void
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

        addSummarizeItemToMenu(protos)

        addElementsResourceLoading(protos)

        // Parses the command and runs it with the command executor.
        replaceStub("executeCommand", 4) {
            """
                ${protos.parse(commandClass, bytes = "p1", result = "v0", registry = "v1")}
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
 * Parses the app's protobuf messages in smali.
 *
 * @param messageClass GeneratedMessageLite.
 */
private class Protos(private val context: BytecodePatchContext, private val messageClass: String) {
    /**
     * @return Smali that parses the bytes of a register as a message of the type into the result register.
     *         The registry register is overwritten.
     */
    fun parse(type: String, bytes: String, result: String, registry: String) = """
        sget-object $result, ${context.defaultInstanceField(type)}
        invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
        move-result-object $registry
        invoke-static { $result, $bytes, $registry }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
        move-result-object $result
    """
}

/**
 * @return The static field that holds the default instance of a message type.
 */
private fun BytecodePatchContext.defaultInstanceField(type: String) = classDefBy(type).fields.first { field ->
    AccessFlags.STATIC.isSet(field.accessFlags) && field.type == type
}

/**
 * Adds the summarize item to the menu of a video as one of the app's own items, so the menu bottom sheet
 * and the menu popup show it in their own list. The extension updates the row of the item and handles its tap.
 */
private fun BytecodePatchContext.addSummarizeItemToMenu(protos: Protos) {
    val menuFragmentClass = MenuBottomSheetFragmentFactoryFingerprint.classDef
    // The factory takes the menu after the theme.
    val menuClass = MenuBottomSheetFragmentFactoryFingerprint.method.parameterTypes[1].toString()
    val createRowMethod = MenuBottomSheetCreateRowFingerprint.method
    val menuItemClass = createRowMethod.parameterTypes.first().toString()
    val rowTapMatch = MenuBottomSheetRowTapFingerprint.match()
    val rowClass = rowTapMatch.instructionMatches[2].instruction.getReference<TypeReference>()!!.type
    val rowMenuItemField = rowTapMatch.instructionMatches[3].getFieldReference()

    // The menu of a video is shown either as a bottom sheet or as a popup.
    val showMenuMethod = FeedFlyoutBufferObjectFingerprint.method
    val showBottomSheetIndex = showMenuMethod.indexOfFirstInstructionOrThrow(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf(menuClass), returnType = "V")
    )

    // Shows the menu next to the view of its button.
    val showPopupIndex = showMenuMethod.indexOfFirstInstructionOrThrow(
        methodCall(
            opcodes = listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE),
            parameters = listOf(menuClass, "Landroid/view/View;", "Ljava/lang/Object;", "L"),
            returnType = "V"
        )
    )
    val showPopupMethod = showMenuMethod.getInstruction(showPopupIndex).getReference<MethodReference>()!!
    val popupMenu = findPopupMenu(classDefBy(showPopupMethod.definingClass), menuItemClass, rowClass)

    // Adds the item to the menu before the call that shows the menu, and updates the rows of a popup after it.
    fun MutableMethod.addMenuItemBefore(index: Int, isPopup: Boolean) {
        val (callerRegister, menuRegister) = getInstruction(index).registersUsed
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
        listOf(showBottomSheetIndex, showPopupIndex).sortedDescending().forEach { index ->
            addMenuItemBefore(index, index == showPopupIndex)
        }
    }

    // Lists that are not built with Litho, such as the History page, show the popup from the menu button
    // with the same call.
    FullHistoryFlyoutBufferObjectFingerprint.method.apply {
        addMenuItemBefore(indexOfFirstInstructionOrThrow(methodCall(reference = showPopupMethod)), true)
    }

    val menus = listOf(
        MenuRows(menuFragmentClass.type, createRowMethod, findRowsField(menuFragmentClass)),
        MenuRows(popupMenu.type, popupMenu.createRowMethod, popupMenu.rowsField)
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

            findMutableMethodOf(menu.createRowMethod).addInstructions(
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
    for (rowTapMethod in listOf(rowTapMatch.method, popupMenu.rowTapMethod)) {
        mutableClassDefBy(rowTapMethod.definingClass).findMutableMethodOf(rowTapMethod).addInstructionsWithLabels(
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
    // that handles their taps. The presenter dismisses the popup and reads the menu item it shows.
    val itemTapMatch = Fingerprint(
        name = "onClick",
        returnType = "V",
        parameters = listOf("Landroid/view/View;"),
        filters = listOf(
            methodCall(reference = popupMenu.dismissMethod),
            fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
            checkCast(menuItemClass, location = MatchAfterImmediately())
        )
    ).match()
    val itemField = itemTapMatch.instructionMatches[1].getFieldReference()
    itemTapMatch.method.addInstructionsWithLabels(
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

    for ((stub, type) in listOf("parseMenu" to menuClass, "parseMenuItem" to menuItemClass)) {
        replaceStub(stub, 3) {
            """
                ${protos.parse(type, bytes = "p0", result = "v0", registry = "v1")}
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
        """
            instance-of v0, p0, ${popupMenu.type}
            if-eqz v0, :not_popup_menu
            check-cast p0, ${popupMenu.type}
            invoke-virtual { p0 }, ${popupMenu.dismissMethod.toReference()}
            :not_popup_menu
            return v0
        """
    }

    replaceStub("isMenuItem", 1) {
        """
            instance-of p0, p0, $menuItemClass
            return p0
        """
    }

    replaceStub("getRowMenuItem", 2) {
        """
            instance-of v0, p0, $rowClass
            if-eqz v0, :not_menu_item_row
            check-cast p0, $rowClass
            iget-object p0, p0, $rowMenuItemField
            return-object p0
            :not_menu_item_row
            const/4 p0, 0x0
            return-object p0
        """
    }

    restoreMenuRowTextConstraints()
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
    val type: String,
    val createRowMethod: Method,
    val rowsField: FieldReference,
    val dismissMethod: Method,
    val rowTapMethod: Method,
)

private fun BytecodePatchContext.findPopupMenu(
    popupClass: ClassDef,
    menuItemClass: String,
    rowClass: String,
): PopupMenu {
    val createRowMatch = PopupMenuCreateRowFingerprint.match(popupClass)
    val createRowMethod = createRowMatch.method
    check(createRowMethod.parameterTypes.first().toString() == menuItemClass) {
        "Unexpected popup row creation method: $createRowMethod"
    }
    // The popup creates the tap handler of each row with the row.
    val rowTapMethod = Fingerprint(
        definingClass = createRowMatch.instructionMatches.first().instruction.getReference<TypeReference>()!!.type,
        returnType = "V",
        parameters = listOf(rowClass)
    ).method

    return PopupMenu(
        popupClass.type,
        createRowMethod,
        findRowsField(popupClass),
        PopupMenuDismissFingerprint.match(popupClass).method,
        rowTapMethod
    )
}

/**
 * @return The field of a menu with the list of its rows.
 */
private fun BytecodePatchContext.findRowsField(menuClass: ClassDef) = menuClass.fields.single { field ->
    !AccessFlags.STATIC.isSet(field.accessFlags) &&
            generateSequence(field.type) { type -> classDefByOrNull(type)?.superclass }
                .any { type -> type == "Ljava/util/AbstractList;" }
}

/**
 * The Ask chat shows nothing until a watch page response attaches its Elements templates.
 * Lets the extension load the Elements resources of a watch page it fetches,
 * with the processor that loads them from the app's own responses.
 */
private fun BytecodePatchContext.addElementsResourceLoading(protos: Protos) {
    val processorClass = ElementsFrameworkUpdatesFingerprint.classDef
    val frameworkUpdatesClass = ElementsFrameworkUpdatesReaderFingerprint.method.parameterTypes.first().toString()
    // Takes the account of the response, which the processor does not use for Elements resources.
    val processMethod = Fingerprint(
        definingClass = processorClass.type,
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf("L", frameworkUpdatesClass)
    ).method
    val usedResourcesField = processorClass.fields.single { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "Ljava/util/Set;"
    }

    ElementsResourceProcessorConstructorFingerprint.method.apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onElementsResourceProcessorCreated(Ljava/lang/Object;)V"
            )
        }
    }

    replaceStub("parseFrameworkUpdates", 3) {
        """
            ${protos.parse(frameworkUpdatesClass, bytes = "p0", result = "v0", registry = "v1")}
            return-object v0
        """
    }

    replaceStub("processFrameworkUpdates", 3) {
        """
            check-cast p0, ${processorClass.type}
            check-cast p1, $frameworkUpdatesClass
            const/4 v0, 0x0
            invoke-virtual { p0, v0, p1 }, ${processMethod.toReference()}
            return-void
        """
    }

    replaceStub("getUsedElementsResources", 1) {
        """
            check-cast p0, ${processorClass.type}
            iget-object p0, p0, $usedResourcesField
            return-object p0
        """
    }
}

/**
 * The menu bottom sheet reuses its rows. A row without icons, such as the loading download item,
 * gets start constraints that move its text to the place of the icon, and keeps them when it is reused,
 * such as for the summarize item that replaces its loading item, which then shows its icon over its text.
 * Restores the start constraints of an inflated row before each bind.
 * Newer app versions restore them for rows with icons themselves, some only behind a flag.
 */
private fun BytecodePatchContext.restoreMenuRowTextConstraints() {
    MenuRowBindFingerprint.let {
        val textContainerField = it.instructionMatches[1].getFieldReference()
        val constraintsClass = it.instructionMatches[5].instruction.getReference<TypeReference>()!!.type
        val startConstraintFields = it.instructionMatches.takeLast(2).map { match -> match.getFieldReference() }

        // -1 is unset, as the layout of the row does not set them.
        it.method.addInstructionsWithLabels(
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
    }
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

/**
 * @return The field that the matched instruction accesses, which can be declared in a superclass.
 */
private fun Match.InstructionMatch.getFieldReference() = instruction.getReference<FieldReference>()!!
