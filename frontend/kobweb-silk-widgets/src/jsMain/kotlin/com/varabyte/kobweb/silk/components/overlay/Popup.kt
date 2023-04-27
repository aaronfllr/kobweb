package com.varabyte.kobweb.silk.components.overlay

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.css.CSSTransition
import com.varabyte.kobweb.compose.dom.ElementRefScope
import com.varabyte.kobweb.compose.dom.ElementTarget
import com.varabyte.kobweb.compose.dom.disposableRef
import com.varabyte.kobweb.compose.dom.refScope
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.BoxScope
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.*
import com.varabyte.kobweb.silk.components.style.ComponentStyle
import com.varabyte.kobweb.silk.components.style.ComponentVariant
import com.varabyte.kobweb.silk.components.style.toModifier
import com.varabyte.kobweb.silk.defer.deferRender
import com.varabyte.kobweb.silk.defer.renderWithDeferred
import kotlinx.browser.window
import org.jetbrains.compose.web.css.*
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventListener

// A small but comfortable amount of space. Also allows the Tooltip composable to extend up a bit with an arrow while
// still leaving a bit of space to go.
const val DEFAULT_POPUP_OFFSET_PX = 15

/**
 * An enumeration for placing a popup outside of while still being aligned to another.
 *
 *       TL  T  TR
 *    LT +-------+ RT
 *       |       |
 *     L |       | R
 *       |       |
 *    LB +-------+ RB
 *       BL  B  BR
 *
 * Note the difference between e.g. [PopupPlacement.TopLeft] and [PopupPlacement.LeftTop]. The former will place the popup above the target, with
 * left edges aligned, while the latter will place to popup to the left of the target, with top edges aligned.
 *
 * Note that popups should avoid covering the element itself (as that would make the popup go away since it would cause
 * the mouseleave event to fire, removing the popup), so there is no option for `Center` placement.
 */
enum class PopupPlacement {
    TopLeft,
    Top,
    TopRight,
    LeftTop,
    RightTop,
    Left,
    Right,
    LeftBottom,
    RightBottom,
    BottomLeft,
    Bottom,
    BottomRight,
}

val PopupStyle by ComponentStyle(prefix = "silk-") {
    base {
        Modifier.transition(CSSTransition("opacity", 150.ms))
    }
}

// When first declared, popups need to make several passes to set themselves up. First, they need to find the raw
// html elements that will be associated with the popup's location. Then, they need to calculate the width of the popup,
// which requires the raw element of the popup itself.
private sealed interface PopupState {
    object Uninitialized : PopupState

    sealed interface Initialized : PopupState {
        val elements: PopupElements
    }

    class FoundElements(override val elements: PopupElements) : Initialized

    sealed interface Visible : Initialized {
        val modifier: Modifier
    }

    /** State for when we're about to show the popup, but we need a bit of time to calculate its width. */
    class Calculating(override val elements: PopupElements) : Visible {
        override val modifier = Modifier
            // Hack - move the popup out of the way while we calculate its width, or else it can block the cursor
            // causing focus to be gained and lost
            .top((-100).percent).left((-100).percent)
            .opacity(0)
    }
    class Shown(override val elements: PopupElements, placement: PopupPlacement, bounds: DOMRect, offsetPixels: Number) : Visible {
        private fun getAbsModifier(
            placement: PopupPlacement,
            popupBounds: DOMRect,
            targetBounds: DOMRect,
            offsetPixels: Double,
        ): Modifier {
            return when (placement) {
                PopupPlacement.TopLeft -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left).px)
                        .top((window.pageYOffset + targetBounds.top - offsetPixels - popupBounds.height).px)
                }

                PopupPlacement.Top -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left - (popupBounds.width - targetBounds.width) / 2).px)
                        .top((window.pageYOffset + targetBounds.top - offsetPixels - popupBounds.height).px)
                }

                PopupPlacement.TopRight -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left + (targetBounds.width - popupBounds.width)).px)
                        .top((window.pageYOffset + targetBounds.top - offsetPixels - popupBounds.height).px)
                }

                PopupPlacement.LeftTop -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left - offsetPixels - popupBounds.width).px)
                        .top((window.pageYOffset + targetBounds.top).px)

                }

                PopupPlacement.RightTop -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.right + offsetPixels).px)
                        .top((window.pageYOffset + targetBounds.top).px)

                }

                PopupPlacement.Left -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left - offsetPixels - popupBounds.width).px)
                        .top((window.pageYOffset + targetBounds.top - (popupBounds.height - targetBounds.height) / 2).px)
                }

                PopupPlacement.Right -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.right + offsetPixels).px)
                        .top((window.pageYOffset + targetBounds.top - (popupBounds.height - targetBounds.height) / 2).px)
                }

                PopupPlacement.LeftBottom -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left - offsetPixels - popupBounds.width).px)
                        .top((window.pageYOffset + targetBounds.top + (targetBounds.height - popupBounds.height)).px)
                }

                PopupPlacement.RightBottom -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.right + offsetPixels).px)
                        .top((window.pageYOffset + targetBounds.top + (targetBounds.height - popupBounds.height)).px)
                }

                PopupPlacement.BottomLeft -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left).px)
                        .top((window.pageYOffset + targetBounds.bottom + offsetPixels).px)
                }

                PopupPlacement.Bottom -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left - (popupBounds.width - targetBounds.width) / 2).px)
                        .top((window.pageYOffset + targetBounds.bottom + offsetPixels).px)
                }

                PopupPlacement.BottomRight -> {
                    Modifier
                        .left((window.pageXOffset + targetBounds.left + (targetBounds.width - popupBounds.width)).px)
                        .top((window.pageYOffset + targetBounds.bottom + offsetPixels).px)
                }
            }
        }

        override val modifier = run {
            val targetBounds = elements.placementElement.getBoundingClientRect()
            getAbsModifier(
                placement,
                bounds,
                targetBounds,
                offsetPixels.toDouble()
            )
        }

    }
    class Hiding(override val elements: PopupElements, modifier: Modifier) : Visible {
        override val modifier = modifier.opacity(0)
    }
}

private class PopupStateController(
    private val placement: PopupPlacement,
    private val offsetPixels: Number,
    private val showDelayMs: Int,
    private val hideDelayMs: Int,
    private val stayOpenStrategy: StayOpenStrategy,
) {
    private var _state by mutableStateOf<PopupState>(PopupState.Uninitialized)
    val state get() = _state

    private var hideWasPostponed by mutableStateOf(false)

    private var showTimeoutId = -1
    private var hideTimeoutId = -1

    private fun reset() {
        hideWasPostponed = false
        window.clearTimeout(showTimeoutId)
        window.clearTimeout(hideTimeoutId)
    }

    fun initialize(elements: PopupElements) {
        check(state is PopupState.Uninitialized)
        _state = PopupState.FoundElements(elements)
    }

    fun requestShowPopup() {
        val state = _state
        if(state !is PopupState.Initialized) return

        reset()
        showTimeoutId = window.setTimeout({
            this._state = PopupState.Calculating(state.elements)
            // Sometimes, we can end up having a show request happen before a hiding finishes. In that case, we can
            // bypass the calculation step and jump straight into showing the popup
            state.elements.popupElement?.let { finishShowing(it) }
        }, showDelayMs)
    }

    fun updatePopupElement(popupElement: HTMLElement) {
        val state = _state
        check(state is PopupState.Initialized)
        stayOpenStrategy.init(popupElement)
        state.elements.popupElement = popupElement
    }

    fun clearPopupElement() {
        val state = _state
        check(state is PopupState.Initialized)
        state.elements.popupElement = null
    }

    fun finishShowing(popupElement: HTMLElement) {
        val state = _state
        if(state !is PopupState.Calculating) return

        _state = PopupState.Shown(
            state.elements,
            placement,
            popupElement.getBoundingClientRect(),
            offsetPixels
        )
    }

    fun requestHidePopup() {
        val state = _state
        if(state !is PopupState.Visible) return

        reset()
        hideTimeoutId = window.setTimeout({
            if (!stayOpenStrategy.shouldStayOpen) {
                this._state = PopupState.Hiding(state.elements, state.modifier)
            } else {
                hideWasPostponed = true
            }
        }, hideDelayMs)
    }

    fun finishHiding(elements: PopupElements) {
        val state = _state
        if (state !is PopupState.Hiding) return

        _state = PopupState.FoundElements(elements)
        hideWasPostponed = false
        window.clearTimeout(showTimeoutId)
        window.clearTimeout(hideTimeoutId)
    }

    // This needs to be called by the Compose method that creates this controller. Under the hood, it checks State
    // values which ensure that the popup will be recomposed at a later time if a strategy made a request to keep the
    // popup open and then later changed to allowing it to close.
    fun checkIfStayOpenStrategyChanged() {
        if (!stayOpenStrategy.shouldStayOpen && hideWasPostponed) {
            hideWasPostponed = false
            requestHidePopup()
        }
    }
}

private class PopupElements(
    private val controller: PopupStateController,
    srcElement: HTMLElement,
    popupTarget: ElementTarget,
    placementTarget: ElementTarget?
) {
    private fun HTMLElement?.resolve(targetFinder: ElementTarget?): HTMLElement? {
        if (this == null || targetFinder == null) return this
        return targetFinder(startingFrom = this)
    }

    private val requestShowPopupListener = EventListener { controller.requestShowPopup() }
    private val requestHidePopupListener = EventListener { controller.requestHidePopup() }

    val targetElement = srcElement.resolve(popupTarget) ?: error("Target element finder returned null")
    val placementElement = if (placementTarget == null) targetElement else
        (srcElement.resolve(placementTarget) ?: error("Placement element finder returned null"))

    // Kind of a hack, but this field is exposed so that the "Show" step can store its raw element somewhere. That way,
    // if we end up back in the "Calculation" state later (this can happen when you flail your mouse cursor wildly
    // across multiple elements that have popups attached to them), we can fast-forward to the "Show" step.
    var popupElement: HTMLElement? = null

    init {
        targetElement.apply {
            addEventListener("mouseenter", requestShowPopupListener)
            addEventListener("mouseleave", requestHidePopupListener)
            if (matches(":hover")) {
                controller.requestShowPopup()
            }
        }
    }

    fun dispose() {
        targetElement.apply {
            removeEventListener("mouseenter", requestShowPopupListener)
            removeEventListener("mouseleave", requestHidePopupListener)
        }
    }
}

/**
 * Render a general, undecorated composable in a location above and outside of some target element.
 *
 * See also [Tooltip], which wraps your composable in a sort of chat bubble, making it particularly well-suited for
 * text tooltips.
 *
 * Note: For users who are only using silk widgets and not kobweb, then you must call [renderWithDeferred] yourself
 * first, as a parent method that this lives under. See the method for more details.
 *
 * @param target Indicates which element should listen for mouse enter and leave events in order to cause this popup to
 *   show up.
 * @param placementTarget If set, indicates which element the popup should be shown relative to. If not set, the
 *   original [target] will be used.
 * @param showDelayMs If set, there will be a delay before the popup is shown after the mouse enters the target.
 * @param hideDelayMs If set, there will be a delay before the popup is hidden after the mouse leaves the target.
 * @param stayOpenStrategy Once a popup is open, this strategy controls how it should decide to stay open. If no
 *   strategy is passed in, the popup will stay open as long as the mouse is over it or if any child inside of it has
 *   focus. See also: [StayOpenStrategy].
 */
@Composable
fun Popup(
    target: ElementTarget,
    modifier: Modifier = Modifier,
    placement: PopupPlacement = PopupPlacement.Bottom,
    offsetPixels: Number = DEFAULT_POPUP_OFFSET_PX,
    placementTarget: ElementTarget? = null,
    showDelayMs: Int = 0,
    hideDelayMs: Int = 0,
    stayOpenStrategy: StayOpenStrategy? = null,
    variant: ComponentVariant? = null,
    ref: ElementRefScope<HTMLElement>? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val popupStateController = remember {
        PopupStateController(
            placement, offsetPixels,
            showDelayMs.coerceAtLeast(0), hideDelayMs.coerceAtLeast(0),
            stayOpenStrategy ?: CompositeStayOpenStrategy(
                IsMouseOverStayOpenStrategy(),
                HasFocusStayOpenStrategy()
            )
        )
    }
    popupStateController.checkIfStayOpenStrategyChanged()

    // Create a dummy element whose purpose is to search for the target element that we want to attach a popup to.
    Box(
        Modifier.display(DisplayStyle.None),
        ref = disposableRef(target) { element ->
            var popupElements: PopupElements? = null
            try {
                popupElements = PopupElements(popupStateController, element, target, placementTarget)
                popupStateController.initialize(popupElements)
            } catch (_: IllegalStateException) {}
            onDispose {
                popupElements?.dispose()
            }
        }
    )

    deferRender {
        // Copy into local var for smart casting.
        val popupState = (popupStateController.state as? PopupState.Visible) ?: return@deferRender
        Box(
            PopupStyle.toModifier(variant)
                .position(Position.Absolute)
                .then(popupState.modifier)
                .then(modifier)
                .onTransitionEnd { evt ->
                    val state = popupStateController.state
                    if (evt.propertyName == "opacity" && state is PopupState.Hiding) {
                        popupStateController.finishHiding(state.elements)
                    }
                },
            ref = refScope {
                disposableRef { popupElement ->
                    popupStateController.updatePopupElement(popupElement)
                    popupStateController.finishShowing(popupElement)
                    onDispose {
                        popupStateController.clearPopupElement()
                        popupStateController.finishHiding(popupState.elements)
                    }
                }
                add(ref)
            },
            content = content
        )
    }
}
