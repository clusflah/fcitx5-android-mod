package org.fcitx.fcitx5.android.common.ipc;

/** Told when the virtual keyboard window is shown or hidden, e.g. to only sync while typing */
oneway interface IKeyboardVisibilityListener {
   void onKeyboardShown();
   void onKeyboardHidden();
}
