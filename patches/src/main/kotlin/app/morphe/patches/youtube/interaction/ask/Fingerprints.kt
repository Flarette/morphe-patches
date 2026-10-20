/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Registers an engagement panel, such as the Ask panel of the video that is opened,
 * so a command can show it by its id.
 * The panel is the first parameter.
 */
internal object RegisterEngagementPanelFingerprint : Fingerprint(
    classFingerprint = EngagementPanelControllerFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "Z", "L", "Z", "L")
)

/**
 * Describes a get_panel request, which loads an engagement panel or a turn of its chat,
 * such as a turn of the Ask chat.
 * The instruction before the "formData" string reads the form data of the request.
 */
internal object GetPanelRequestDescriptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("params"),
        string("panelId"),
        string("continuation"),
        string("formData"),
        string("query")
    )
)

/**
 * Builds the message of a get_panel request.
 * The instruction before the match reads the continuation of the request.
 */
internal object GetPanelRequestBuilderFingerprint : Fingerprint(
    classFingerprint = GetPanelRequestDescriptionFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z")
    )
)

/**
 * Reads the menu of the menu bottom sheet from its arguments. Its class creates the rows of the menu items
 * with a method that returns an Optional, unlike the older menu bottom sheets that read the same argument.
 */
internal object MenuBottomSheetFragmentFingerprint : Fingerprint(
    filters = listOf(
        string("MENU_BOTTOM_SHEET_FRAGMENT_KEY")
    ),
    custom = { _, classDef ->
        classDef.methods.any { method ->
            method.parameterTypes.size == 1 && method.returnType.endsWith("/util/Optional;")
        }
    }
)

/**
 * Loads the Elements resources, such as templates, that the framework updates of a response attach.
 * One parameter is the framework updates. In newer app versions, this is not the method of the processor
 * that other code calls with the framework updates, but a method that it calls.
 */
internal object ElementsFrameworkUpdatesFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        string("fut elements"),
        string("Failed to process FrameworkUpdateTransport")
    )
)

/**
 * Binds a menu item to a row of the menu bottom sheet, which can be a reused row.
 * The matches from the second are the read of the constraints of the text container of the row,
 * and the writes of the start constraints that a row without icons and detail text gets.
 */
internal object MenuRowBindFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "bottom_sheet_list_item_height"),
        methodCall(smali = "Landroid/widget/LinearLayout;->getLayoutParams()Landroid/view/ViewGroup\$LayoutParams;"),
        opcode(opcode = Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        // Newer app versions check the icons after the cast.
        opcode(opcode = Opcode.IPUT, location = MatchAfterWithin(8)),
        opcode(opcode = Opcode.IPUT, location = MatchAfterImmediately())
    )
)

/**
 * Runs the command of a tapped Elements component, such as a suggestion of Ask.
 * The last instruction gets the command from the command holder of the component.
 */
internal object ElementsTapHandlerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L", "Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->getLocationInWindow([I)V"),
        methodCall(
            returnType = "Lcom/google/protos/youtube/elements/CommandOuterClass\$Command;",
            parameters = listOf()
        )
    )
)

/**
 * Sends the command of a tapped Litho button to the command executor.
 * The last instruction is the call of the command executor.
 */
internal object LithoButtonCommandFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(opcode = Opcode.NEW_INSTANCE),
        opcode(opcode = Opcode.INVOKE_DIRECT, location = MatchAfterImmediately()),
        newInstance(type = "Ljava/util/HashMap;", location = MatchAfterWithin(5)),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            smali = "Ljava/util/HashMap;-><init>(Ljava/util/Map;)V",
            location = MatchAfterWithin(5)
        ),
        string("command_status_callback", location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L", "Ljava/util/Map;"),
            returnType = "V"
        )
    )
)
