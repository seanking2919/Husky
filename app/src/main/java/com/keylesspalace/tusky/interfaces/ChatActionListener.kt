package com.keylesspalace.tusky.interfaces

interface ChatActionListener: LinkListener {
    fun onLoadMore(position: Int)
}