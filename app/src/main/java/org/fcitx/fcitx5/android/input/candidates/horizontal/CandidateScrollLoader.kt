/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * mod: the candidate bar scrolls sideways (HorizontalCandidateComponent lays it out in one
 * LinearLayoutManager row). fcitx only sends the first 16 candidates, so when the row is scrolled near its
 * end the next ones are fetched and appended.
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady

class CandidateScrollLoader(
    private val fcitx: FcitxConnection,
    private val adapter: HorizontalCandidateViewAdapter
) : RecyclerView.OnScrollListener() {

    private var loading = false

    /** the list for which fcitx had nothing more */
    private var exhausted: Array<CandidateWord>? = null

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (dx <= 0 || loading) return
        val have = adapter.candidates
        if (have.isEmpty() || have === exhausted) return
        if (adapter.total >= 0 && have.size >= adapter.total) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (layoutManager.findLastVisibleItemPosition() < have.size - PRELOAD) return
        loading = true
        fcitx.launchOnReady { api ->
            val more = runCatching { api.getCandidates(have.size, PAGE) }.getOrNull() ?: emptyArray()
            recyclerView.post {
                loading = false
                // the candidates changed meanwhile: these belong to the old list
                if (adapter.candidates !== have) return@post
                if (more.isEmpty()) exhausted = have
                else adapter.appendCandidates(more)
            }
        }
    }

    companion object {
        private const val PAGE = 16
        private const val PRELOAD = 8
    }
}
