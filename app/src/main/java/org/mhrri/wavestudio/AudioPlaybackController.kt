package org.mhrri.wavestudio

import android.content.Context

/**
 * Minimal stubs for playback APIs referenced from UI.
 *
 * The project historically had playback support via Media3/ExoPlayer. If playback logic
 * gets refactored out, we keep these methods so MainActivity compiles.
 */
internal interface AudioPlaybackController {
    fun stopPlayback()
    fun playRecording(context: Context, clip: RecordedClip)
    fun seekPlaybackTo(positionMs: Long)
}
