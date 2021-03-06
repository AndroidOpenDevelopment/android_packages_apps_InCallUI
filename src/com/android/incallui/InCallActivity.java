/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.app.ActionBar;
import android.app.FragmentTransaction;
import android.app.ActionBar.Tab;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.phone.common.animation.AnimUtils;
import com.android.phone.common.animation.AnimationListenerAdapter;
import com.android.contacts.common.interactions.TouchPointManager;
import com.android.incallui.Call.State;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone app "in call" screen.
 */
public class InCallActivity extends Activity {

    public static final String SHOW_DIALPAD_EXTRA = "InCallActivity.show_dialpad";
    public static final String DIALPAD_TEXT_EXTRA = "InCallActivity.dialpad_text";
    public static final String NEW_OUTGOING_CALL = "InCallActivity.new_outgoing_call";
    private static final String ACTION_SUPP_SERVICE_FAILURE =
            "org.codeaurora.ACTION_SUPP_SERVICE_FAILURE";
    private CallButtonFragment mCallButtonFragment;
    private CallCardFragment mCallCardFragment;
    private AnswerFragment mAnswerFragment;
    private DialpadFragment mDialpadFragment;
    private ConferenceManagerFragment mConferenceManagerFragment;
    private FragmentManager mChildFragmentManager;
    private SuppServFailureNotificationReceiver mReceiver;
    private boolean mIsForegroundActivity;
    private AlertDialog mDialog;

    /** Use to pass 'showDialpad' from {@link #onNewIntent} to {@link #onResume} */
    private boolean mShowDialpadRequested;

    /** Use to determine if the dialpad should be animated on show. */
    private boolean mAnimateDialpadOnShow;

    /** Use to determine the DTMF Text which should be pre-populated in the dialpad. */
    private String mDtmfText;

    /** Use to pass parameters for showing the PostCharDialog to {@link #onResume} */
    private boolean mShowPostCharWaitDialogOnResume;
    private String mShowPostCharWaitDialogCallId;
    private String mShowPostCharWaitDialogChars;

    private boolean mIsLandscape;
    private Animation mSlideIn;
    private Animation mSlideOut;

    private final int TAB_COUNT_ONE = 1;
    private final int TAB_COUNT_TWO = 2;
    private final int TAB_POSITION_FIRST = 0;

    private Tab[] mDsdaTab = new Tab[TAB_COUNT_TWO];
    private boolean[] mDsdaTabAdd = {false, false};

    AnimationListenerAdapter mSlideOutListener = new AnimationListenerAdapter() {
        @Override
        public void onAnimationEnd(Animation animation) {
            showDialpad(false);
        }
    };

    // This enum maps to Phone.SuppService defined in telephony
    private enum SuppService {
        UNKNOWN, SWITCH, SEPARATE, TRANSFER, CONFERENCE, REJECT, HANGUP;
    }

    /**
     * Used to determine if a change in orientation has occurred.
     */
    private static int mCurrentOrientation = Configuration.ORIENTATION_UNDEFINED;

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);

        // set this flag so this activity will stay in front of the keyguard
        // Have the WindowManager filter out touch events that are "too fat".
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);
        boolean isDsdaEnabled = CallList.getInstance().isDsdaEnabled();
        if (isDsdaEnabled) {
            requestWindowFeature(Window.FEATURE_ACTION_BAR);
            getActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
            getActionBar().setDisplayShowTitleEnabled(false);
            getActionBar().setDisplayShowHomeEnabled(false);
        } else {
            requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
            if (getActionBar() != null) {
                getActionBar().setDisplayHomeAsUpEnabled(true);
                getActionBar().setDisplayShowTitleEnabled(true);
                getActionBar().hide();
            }
        }

        // TODO(klp): Do we need to add this back when prox sensor is not available?
        // lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);

        initializeInCall();

        internalResolveIntent(getIntent());

        mIsLandscape = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;

        final boolean isRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) ==
                View.LAYOUT_DIRECTION_RTL;

        if (mIsLandscape) {
            mSlideIn = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_in_left : R.anim.dialpad_slide_in_right);
            mSlideOut = AnimationUtils.loadAnimation(this,
                    isRtl ? R.anim.dialpad_slide_out_left : R.anim.dialpad_slide_out_right);
        } else {
            mSlideIn = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_in_bottom);
            mSlideOut = AnimationUtils.loadAnimation(this, R.anim.dialpad_slide_out_bottom);
        }

        mSlideIn.setInterpolator(AnimUtils.EASE_IN);
        mSlideOut.setInterpolator(AnimUtils.EASE_OUT);

        mSlideOut.setAnimationListener(mSlideOutListener);

        if (icicle != null) {
            // If the dialpad was shown before, set variables indicating it should be shown and
            // populated with the previous DTMF text.  The dialpad is actually shown and populated
            // in onResume() to ensure the hosting CallCardFragment has been inflated and is ready
            // to receive it.
            mShowDialpadRequested = icicle.getBoolean(SHOW_DIALPAD_EXTRA);
            mAnimateDialpadOnShow = false;
            mDtmfText = icicle.getString(DIALPAD_TEXT_EXTRA);
        }
        if (isDsdaEnabled ) {
            initializeDsdaSwitchTab();
        }
        // Register for supplementary service failure  broadcasts.
        mReceiver = new SuppServFailureNotificationReceiver();
        IntentFilter intentFilter =
                new IntentFilter(ACTION_SUPP_SERVICE_FAILURE);
        intentFilter.addAction(ACTION_SUPP_SERVICE_FAILURE);
        registerReceiver(mReceiver, intentFilter);
        Log.d(this, "onCreate(): exit");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putBoolean(SHOW_DIALPAD_EXTRA, mCallButtonFragment.isDialpadVisible());
        if (mDialpadFragment != null) {
            out.putString(DIALPAD_TEXT_EXTRA, mDialpadFragment.getDtmfText());
        }
    }

    @Override
    protected void onStart() {
        Log.d(this, "onStart()...");
        super.onStart();

        // setting activity should be last thing in setup process
        InCallPresenter.getInstance().setActivity(this);

        // It is possible that the activity restarted because orientation changed.
        // Notify listeners if orientation changed.
        doOrientationChanged(getResources().getConfiguration().orientation);
        InCallPresenter.getInstance().onActivityStarted();
    }

    @Override
    protected void onResume() {
        Log.i(this, "onResume()...");
        super.onResume();

        mIsForegroundActivity = true;
        InCallPresenter.getInstance().onUiShowing(true);

        if (mShowDialpadRequested) {
            mCallButtonFragment.displayDialpad(true /* show */,
                    mAnimateDialpadOnShow /* animate */);
            mShowDialpadRequested = false;
            mAnimateDialpadOnShow = false;

            if (mDialpadFragment != null) {
                mDialpadFragment.setDtmfText(mDtmfText);
                mDtmfText = null;
            }
        }

        if (mShowPostCharWaitDialogOnResume) {
            showPostCharWaitDialog(mShowPostCharWaitDialogCallId, mShowPostCharWaitDialogChars);
        }
    }

    // onPause is guaranteed to be called when the InCallActivity goes
    // in the background.
    @Override
    protected void onPause() {
        Log.d(this, "onPause()...");
        super.onPause();

        mIsForegroundActivity = false;

        if (mDialpadFragment != null ) {
            mDialpadFragment.onDialerKeyUp(null);
        }

        InCallPresenter.getInstance().onUiShowing(false);
    }

    @Override
    protected void onStop() {
        Log.d(this, "onStop()...");

        InCallPresenter.getInstance().updateIsChangingConfigurations();
        InCallPresenter.getInstance().onActivityStopped();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + this);

        InCallPresenter.getInstance().updateIsChangingConfigurations();
        InCallPresenter.getInstance().setActivity(null);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    /**
     * Returns true when theActivity is in foreground (between onResume and onPause).
     */
    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    private boolean hasPendingErrorDialog() {
        return mDialog != null;
    }

    /**
     * Dismisses the in-call screen.
     *
     * We never *really* finish() the InCallActivity, since we don't want to get destroyed and then
     * have to be re-created from scratch for the next call.  Instead, we just move ourselves to the
     * back of the activity stack.
     *
     * This also means that we'll no longer be reachable via the BACK button (since moveTaskToBack()
     * puts us behind the Home app, but the home app doesn't allow the BACK key to move you any
     * farther down in the history stack.)
     *
     * (Since the Phone app itself is never killed, this basically means that we'll keep a single
     * InCallActivity instance around for the entire uptime of the device.  This noticeably improves
     * the UI responsiveness for incoming calls.)
     */
    @Override
    public void finish() {
        Log.i(this, "finish().  Dialog showing: " + (mDialog != null));

        // skip finish if we are still showing a dialog.
        if (!hasPendingErrorDialog() && !mAnswerFragment.hasPendingDialogs()) {
            super.finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(this, "onNewIntent: intent = " + intent);

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallActivity instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallActivity needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.d(this, "onBackPressed()...");

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (!mCallCardFragment.isVisible()) {
            return;
        }

        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            // Notify CallButtonPresenter to remove Dialpad and update UI
            mCallButtonFragment.getPresenter().showDialpadClicked(false);
            return;
        } else if (mConferenceManagerFragment.isVisible()) {
            mConferenceManagerFragment.setVisible(false);
            return;
        }

        // Always disable the Back key while an incoming call is ringing
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            Log.d(this, "Consume Back press for an incoming call");
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // push input to the dialer.
        if (mDialpadFragment != null && (mDialpadFragment.isVisible()) &&
                (mDialpadFragment.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = InCallPresenter.getInstance().handleCallKey();
                if (!handled) {
                    Log.w(this, "InCallActivity should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Ringer silencing handled by PhoneWindowManager.
                break;

            case KeyEvent.KEYCODE_MUTE:
                // toggle mute
                TelecomAdapter.getInstance().mute(!AudioModeProvider.getInstance().getMute());
                return true;

            // Various testing/debugging features, enabled ONLY when VERBOSE == true.
            case KeyEvent.KEYCODE_SLASH:
                if (Log.VERBOSE) {
                    Log.v(this, "----------- InCallActivity View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    Log.d(this, "View dump:" + decorView);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                // TODO: Dump phone state?
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        Log.v(this, "handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.
        if (mDialpadFragment != null && mDialpadFragment.isVisible()) {
            return mDialpadFragment.onDialerKeyDown(event);

            // TODO: If the dialpad isn't currently visible, maybe
            // consider automatically bringing it up right now?
            // (Just to make sure the user sees the digits widget...)
            // But this probably isn't too critical since it's awkward to
            // use the hard keyboard while in-call in the first place,
            // especially now that the in-call UI is portrait-only...
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        InCallPresenter.getInstance().getProximitySensor().onConfigurationChanged(config);
        Log.d(this, "onConfigurationChanged "+config.orientation);

        doOrientationChanged(config.orientation);
        super.onConfigurationChanged(config);
    }


    private void doOrientationChanged(int orientation) {
        Log.d(this, "doOrientationChanged prevOrientation=" + mCurrentOrientation +
                " newOrientation=" + orientation);
        // Check to see if the orientation changed to prevent triggering orientation change events
        // for other configuration changes.
        if (orientation != mCurrentOrientation) {
            mCurrentOrientation = orientation;
            InCallPresenter.getInstance().onDeviceRotationChange(
                    getWindowManager().getDefaultDisplay().getRotation());
            InCallPresenter.getInstance().onDeviceOrientationChange(mCurrentOrientation);
        }
    }

    public CallButtonFragment getCallButtonFragment() {
        return mCallButtonFragment;
    }

    public CallCardFragment getCallCardFragment() {
        return mCallCardFragment;
    }

    private void internalResolveIntent(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                final boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                Log.d(this, "- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                relaunchedFromDialer(showDialpad);
            }

            if (intent.getBooleanExtra(NEW_OUTGOING_CALL, false)) {
                intent.removeExtra(NEW_OUTGOING_CALL);
                Call call = CallList.getInstance().getOutgoingCall();
                if (call == null) {
                    call = CallList.getInstance().getPendingOutgoingCall();
                }

                Bundle extras = null;
                if (call != null) {
                    extras = call.getTelecommCall().getDetails().getExtras();
                }
                if (extras == null) {
                    // Initialize the extras bundle to avoid NPE
                    extras = new Bundle();
                }


                Point touchPoint = null;
                if (TouchPointManager.getInstance().hasValidPoint()) {
                    // Use the most immediate touch point in the InCallUi if available
                    touchPoint = TouchPointManager.getInstance().getPoint();
                } else {
                    // Otherwise retrieve the touch point from the call intent
                    if (call != null) {
                        touchPoint = (Point) extras.getParcelable(TouchPointManager.TOUCH_POINT);
                    }
                }
                mCallCardFragment.animateForNewOutgoingCall(touchPoint);

                /*
                 * If both a phone account handle and a list of phone accounts to choose from are
                 * missing, then disconnect the call because there is no way to place an outgoing
                 * call.
                 * The exception is emergency calls, which may be waiting for the ConnectionService
                 * to set the PhoneAccount during the PENDING_OUTGOING state.
                 */
                if (call != null && !isEmergencyCall(call)) {
                    final List<PhoneAccountHandle> phoneAccountHandles = extras
                            .getParcelableArrayList(android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS);
                    if (call.getAccountHandle() == null &&
                            (phoneAccountHandles == null || phoneAccountHandles.isEmpty())) {
                        TelecomAdapter.getInstance().disconnectCall(call.getId());
                    }
                }
            }

            Call pendingAccountSelectionCall = CallList.getInstance().getWaitingForAccountCall();
            if (pendingAccountSelectionCall != null) {
                mCallCardFragment.setVisible(false);
                Bundle extras = pendingAccountSelectionCall
                        .getTelecommCall().getDetails().getExtras();

                final List<PhoneAccountHandle> phoneAccountHandles;
                if (extras != null) {
                    phoneAccountHandles = extras.getParcelableArrayList(
                            android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS);
                } else {
                    phoneAccountHandles = new ArrayList<>();
                }

                SelectPhoneAccountDialogFragment.showAccountDialog(getFragmentManager(),
                        phoneAccountHandles);
            } else {
                mCallCardFragment.setVisible(true);
            }

            return;
        }
    }

    private boolean isEmergencyCall(Call call) {
        final Uri handle = call.getHandle();
        if (handle == null) {
            return false;
        }
        return PhoneNumberUtils.isEmergencyNumber(handle.getSchemeSpecificPart());
    }

    private void relaunchedFromDialer(boolean showDialpad) {
        mShowDialpadRequested = showDialpad;
        mAnimateDialpadOnShow = true;

        if (mShowDialpadRequested) {
            // If there's only one line in use, AND it's on hold, then we're sure the user
            // wants to use the dialpad toward the exact line, so un-hold the holding line.
            final Call call = CallList.getInstance().getActiveOrBackgroundCall();
            if (call != null && call.getState() == State.ONHOLD) {
                TelecomAdapter.getInstance().unholdCall(call.getId());
            }
        }
    }

    private void initializeInCall() {
        if (mCallCardFragment == null) {
            mCallCardFragment = (CallCardFragment) getFragmentManager()
                    .findFragmentById(R.id.callCardFragment);
        }

        mChildFragmentManager = mCallCardFragment.getChildFragmentManager();

        if (mCallButtonFragment == null) {
            mCallButtonFragment = (CallButtonFragment) mChildFragmentManager
                    .findFragmentById(R.id.callButtonFragment);
            mCallButtonFragment.getView().setVisibility(View.INVISIBLE);
        }

        if (mAnswerFragment == null) {
            mAnswerFragment = (AnswerFragment) mChildFragmentManager
                    .findFragmentById(R.id.answerFragment);
        }

        if (mConferenceManagerFragment == null) {
            mConferenceManagerFragment = (ConferenceManagerFragment) getFragmentManager()
                    .findFragmentById(R.id.conferenceManagerFragment);
            mConferenceManagerFragment.getView().setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Simulates a user click to hide the dialpad. This will update the UI to show the call card,
     * update the checked state of the dialpad button, and update the proximity sensor state.
     */
    public void hideDialpadForDisconnect() {
        mCallButtonFragment.displayDialpad(false /* show */, true /* animate */);
    }

    public void dismissKeyguard(boolean dismiss) {
        if (dismiss) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void showDialpad(boolean showDialpad) {
        // If the dialpad is being shown and it has not already been loaded, replace the dialpad
        // placeholder with the actual fragment before continuing.
        if (mDialpadFragment == null && showDialpad) {
            final FragmentTransaction loadTransaction = mChildFragmentManager.beginTransaction();
            View fragmentContainer = findViewById(R.id.dialpadFragmentContainer);
            mDialpadFragment = new DialpadFragment();
            loadTransaction.replace(fragmentContainer.getId(), mDialpadFragment,
                    DialpadFragment.class.getName());
            loadTransaction.commitAllowingStateLoss();
            mChildFragmentManager.executePendingTransactions();
        }

        final FragmentTransaction ft = mChildFragmentManager.beginTransaction();
        if (showDialpad) {
            ft.show(mDialpadFragment);
        } else {
            ft.hide(mDialpadFragment);
        }
        ft.commitAllowingStateLoss();
    }

    public void displayDialpad(boolean showDialpad, boolean animate) {
        // If the dialpad is already visible, don't animate in. If it's gone, don't animate out.
        if ((showDialpad && isDialpadVisible()) || (!showDialpad && !isDialpadVisible())) {
            return;
        }
        // We don't do a FragmentTransaction on the hide case because it will be dealt with when
        // the listener is fired after an animation finishes.
        if (!animate) {
            showDialpad(showDialpad);
        } else {
            if (showDialpad) {
                showDialpad(true);
                mDialpadFragment.animateShowDialpad();
            }
            mCallCardFragment.onDialpadVisiblityChange(showDialpad);
            mDialpadFragment.getView().startAnimation(showDialpad ? mSlideIn : mSlideOut);
        }

        InCallPresenter.getInstance().getProximitySensor().onDialpadVisible(showDialpad);
    }

    public boolean isDialpadVisible() {
        return mDialpadFragment != null && mDialpadFragment.isVisible();
    }

    public void showConferenceCallManager() {
        mConferenceManagerFragment.setVisible(true);
    }

    public void showPostCharWaitDialog(String callId, String chars) {
        if (isForegroundActivity()) {
            final PostCharDialogFragment fragment = new PostCharDialogFragment(callId,  chars);
            fragment.show(getFragmentManager(), "postCharWait");

            mShowPostCharWaitDialogOnResume = false;
            mShowPostCharWaitDialogCallId = null;
            mShowPostCharWaitDialogChars = null;
        } else {
            mShowPostCharWaitDialogOnResume = true;
            mShowPostCharWaitDialogCallId = callId;
            mShowPostCharWaitDialogChars = chars;
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mCallCardFragment != null) {
            mCallCardFragment.dispatchPopulateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    public void maybeShowErrorDialogOnDisconnect(DisconnectCause disconnectCause) {
        Log.d(this, "maybeShowErrorDialogOnDisconnect");

        if (!isFinishing() && !TextUtils.isEmpty(disconnectCause.getDescription())
                && (disconnectCause.getCode() == DisconnectCause.ERROR ||
                        disconnectCause.getCode() == DisconnectCause.RESTRICTED)) {
            showErrorDialog(disconnectCause.getDescription());
        }
    }

    public void dismissPendingDialogs() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        mAnswerFragment.dismissPendingDialogues();
    }

    /**
     * Handle a failure notification for a supplementary service
     * (i.e. conference, switch, separate, transfer, etc.).
     */
    void onSuppServiceFailed(int service) {
        Log.d(this, "onSuppServiceFailed: " + service);
        SuppService  result = SuppService.values()[service];
        int errorMessageResId;

        switch (result) {
            case SWITCH:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch calls")
                errorMessageResId = R.string.incall_error_supp_service_switch;
                break;

            case SEPARATE:
                // Attempt to separate a call from a conference call
                // failed ("Failed to separate out call")
                errorMessageResId = R.string.incall_error_supp_service_separate;
                break;

            case TRANSFER:
                // Attempt to connect foreground and background calls to
                // each other (and hanging up user's line) failed ("Call
                // transfer failed")
                errorMessageResId = R.string.incall_error_supp_service_transfer;
                break;

            case CONFERENCE:
                // Attempt to add a call to conference call failed
                // ("Conference call failed")
                errorMessageResId = R.string.incall_error_supp_service_conference;
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageResId = R.string.incall_error_supp_service_reject;
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageResId = R.string.incall_error_supp_service_hangup;
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageResId = R.string.incall_error_supp_service_unknown;
                break;
        }
        final CharSequence msg = getResources().getText(errorMessageResId);
        showErrorDialog(msg);
    }

    /**
     * Utility function to bring up a generic "error" dialog.
     */
    private void showErrorDialog(CharSequence msg) {
        Log.i(this, "Show Dialog: " + msg);

        dismissPendingDialogs();

        mDialog = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onDialogDismissed();
                    }})
                .setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        onDialogDismissed();
                    }})
                .create();

        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mDialog.show();
    }

    private void onDialogDismissed() {
        mDialog = null;
        InCallPresenter.getInstance().onDismissDialog();
    }

   private void initializeDsdaSwitchTab() {
        int phoneCount = CallList.PHONE_COUNT;
        ActionBar bar = getActionBar();
        View[] mDsdaTabLayout = new View[phoneCount];
        TypedArray icons = getResources().obtainTypedArray(R.array.sim_icons);
        int[] subString = {R.string.sub_1, R.string.sub_2};

        for (int i = 0; i < phoneCount; i++) {
            mDsdaTabLayout[i] = getLayoutInflater()
                    .inflate(R.layout.msim_tab_sub_info, null);

            ((ImageView)mDsdaTabLayout[i].findViewById(R.id.tabSubIcon))
                    .setBackground(icons.getDrawable(i));

            ((TextView)mDsdaTabLayout[i].findViewById(R.id.tabSubText))
                    .setText(subString[i]);

            mDsdaTab[i] = bar.newTab().setCustomView(mDsdaTabLayout[i])
                    .setTabListener(new TabListener(i));
        }
    }

    public void updateDsdaTab() {
        int phoneCount = CallList.PHONE_COUNT;
        ActionBar bar = getActionBar();

        for (int i = 0; i < phoneCount; i++) {
            long[] subId = CallList.getInstance().getSubId(i);
            if (subId != null && CallList.getInstance().hasAnyLiveCall(subId[0])) {
                if (!mDsdaTabAdd[i]) {
                    addDsdaTab(i);
                }
            } else {
                removeDsdaTab(i);
            }
        }

        updateDsdaTabSelection();
    }

    private void addDsdaTab(int subId) {
        ActionBar bar = getActionBar();
        int tabCount = bar.getTabCount();

        if (tabCount < subId) {
            bar.addTab(mDsdaTab[subId], false);
        } else {
            bar.addTab(mDsdaTab[subId], subId, false);
        }
        mDsdaTabAdd[subId] = true;
        Log.d(this, "addDsdaTab, subId = " + subId + " tab count = " + tabCount);
    }

    private void removeDsdaTab(int subId) {
        ActionBar bar = getActionBar();
        int tabCount = bar.getTabCount();

        for (int i = 0; i < tabCount; i++) {
            if (bar.getTabAt(i).equals(mDsdaTab[subId])) {
                bar.removeTab(mDsdaTab[subId]);
                mDsdaTabAdd[subId] = false;
                return;
            }
        }
        Log.d(this, "removeDsdaTab, subId = " + subId + " tab count = " + tabCount);
    }

    private void updateDsdaTabSelection() {
        ActionBar bar = getActionBar();
        int barCount = bar.getTabCount();

        if (barCount == TAB_COUNT_ONE) {
            bar.selectTab(bar.getTabAt(TAB_POSITION_FIRST));
        } else if (barCount == TAB_COUNT_TWO) {
            int phoneId = CallList.getInstance().getPhoneId(CallList
                    .getInstance().getActiveSubscription());
            bar.selectTab(bar.getTabAt(phoneId));
        }
    }

    private class TabListener implements ActionBar.TabListener {
        int mPhoneId;

        public TabListener(int phoneId) {
            mPhoneId = phoneId;
        }

        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            ActionBar bar = getActionBar();
            int tabCount = bar.getTabCount();
                Log.i(this, "onTabSelected mPhoneId:" + mPhoneId);
            //Don't setActiveSubscription if tab count is 1.This is to avoid
            //setting active subscription automatically when call on one sub
            //ends and it's corresponding tab is removed.For such cases active
            //subscription will be set by InCallPresenter.attemptFinishActivity.
            long[] subId = CallList.getInstance().getSubId(mPhoneId);
            if (tabCount != TAB_COUNT_ONE && CallList.getInstance().hasAnyLiveCall(subId[0])
                    && (CallList.getInstance().getActiveSubscription() != subId[0])) {
                Log.i(this, "Switch to other active sub: " + subId[0]);
                TelecomAdapter.getInstance().switchToOtherActiveSub(
                        String.valueOf(subId[0]), false);
            }
        }

        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
        }

        public void onTabReselected(Tab tab, FragmentTransaction ft) {
        }
    }

    public class SuppServFailureNotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(this, "Action: " + action);

            if (action.equals(ACTION_SUPP_SERVICE_FAILURE)) {
                int service = intent.getIntExtra("supp_serv_failure", 0);
                Log.d(this, "SuppServFailureNotificationReceiver: " + service);
                onSuppServiceFailed(service);
            }
        }
    }
}
