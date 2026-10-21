package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing with notifications. Android only lets an app see other apps' media sessions (the Now playing pill)
 * through an enabled notification listener, and on Google TV there is no screen to enable one, so once over adb:
 *   cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
 */
public final class MediaListener extends NotificationListenerService { }
