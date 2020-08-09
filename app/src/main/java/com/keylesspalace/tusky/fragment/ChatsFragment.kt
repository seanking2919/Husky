package com.keylesspalace.tusky.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.arch.core.util.Function
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import at.connyduck.sparkbutton.helpers.Utils
import com.keylesspalace.tusky.R
import com.keylesspalace.tusky.adapter.ChatsAdapter
import com.keylesspalace.tusky.adapter.StatusBaseViewHolder
import com.keylesspalace.tusky.adapter.TimelineAdapter
import com.keylesspalace.tusky.appstore.*
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.di.Injectable
import com.keylesspalace.tusky.entity.Chat
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.interfaces.*
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.repository.ChatRepository
import com.keylesspalace.tusky.repository.Placeholder
import com.keylesspalace.tusky.repository.TimelineRepository
import com.keylesspalace.tusky.repository.TimelineRequestMode
import com.keylesspalace.tusky.util.*
import com.keylesspalace.tusky.view.EndlessOnScrollListener
import com.keylesspalace.tusky.viewdata.ChatViewData
import com.uber.autodispose.AutoDispose
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Consumer
import kotlinx.android.synthetic.main.fragment_timeline.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ChatsFragment : BaseFragment(), Injectable, AccountActionListener, RefreshableFragment, ReselectableFragment, ChatActionListener, OnRefreshListener {
    private val TAG = "ChatsF" // logging tag
    private val LOAD_AT_ONCE = 30

    @Inject
    lateinit var eventHub: EventHub
    @Inject
    lateinit var api: MastodonApi
    @Inject
    lateinit var accountManager: AccountManager
    @Inject
    lateinit var chatRepo: ChatRepository

    lateinit var adapter: ChatsAdapter

    lateinit var layoutManager: LinearLayoutManager

    lateinit var scrollListener: EndlessOnScrollListener

    private var hideFab = false
    private var eventRegistered = false
    private var isSwipeToRefreshEnabled = true
    private var isNeedRefresh = false
    private var didLoadEverythingBottom = false
    private var initialUpdateFailed = false

    private val chats = PairedList<Either<Placeholder, Chat>, ChatViewData?>(Function<Either<Placeholder, Chat>, ChatViewData?> {input ->
        val chat = input.asRightOrNull()
        if (chat != null) {
            ViewDataUtils.chatToViewData(chat)
        } else {
            val (id) = input.asLeft()
            ChatViewData.Placeholder(id, false)
        }
    })

    private val listUpdateCallback: ListUpdateCallback = object : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) {
            if (isAdded) {
                adapter.notifyItemRangeInserted(position, count)
                val context = context
                if (position == 0 && context != null) {
                    if (isSwipeToRefreshEnabled)
                        recyclerView.scrollBy(0, Utils.dpToPx(context, -30))
                    else recyclerView.scrollToPosition(0)
                }
            }
        }

        override fun onRemoved(position: Int, count: Int) {
            adapter.notifyItemRangeRemoved(position, count)
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            adapter.notifyItemMoved(fromPosition, toPosition)
        }

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            adapter.notifyItemRangeChanged(position, count, payload)
        }
    }

    private val diffCallback: DiffUtil.ItemCallback<ChatViewData> = object : DiffUtil.ItemCallback<ChatViewData>() {
        override fun areItemsTheSame(oldItem: ChatViewData, newItem: ChatViewData): Boolean {
            return oldItem.viewDataId == newItem.viewDataId
        }

        override fun areContentsTheSame(oldItem: ChatViewData, newItem: ChatViewData): Boolean {
            return false //Items are different always. It allows to refresh timestamp on every view holder update
        }

        override fun getChangePayload(oldItem: ChatViewData, newItem: ChatViewData): Any? {
            return if (oldItem.deepEquals(newItem)) {
                //If items are equal - update timestamp only
                listOf(StatusBaseViewHolder.Key.KEY_CREATED)
            } else  // If items are different - update a whole view holder
                null
        }
    }

    private val differ = AsyncListDiffer(listUpdateCallback,
            AsyncDifferConfig.Builder(diffCallback).build())

    private val dataSource: TimelineAdapter.AdapterDataSource<ChatViewData> = object : TimelineAdapter.AdapterDataSource<ChatViewData> {
        override fun getItemCount(): Int {
            return differ.currentList.size
        }

        override fun getItemAt(pos: Int): ChatViewData {
            return differ.currentList[pos]
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)

        val statusDisplayOptions = StatusDisplayOptions(
                preferences.getBoolean("animateGifAvatars", false),
                accountManager.activeAccount!!.mediaPreviewEnabled,
                preferences.getBoolean("absoluteTimeView", false),
                preferences.getBoolean("showBotOverlay", true),
                false, CardViewMode.NONE,false
        )
        adapter = ChatsAdapter(dataSource, statusDisplayOptions, this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_timeline, container, false)

        swipeRefreshLayout.isEnabled = isSwipeToRefreshEnabled
        swipeRefreshLayout.setOnRefreshListener(this)
        swipeRefreshLayout.setColorSchemeResources(R.color.tusky_blue)

        // TODO: a11y
        recyclerView.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(view.context)
        recyclerView.layoutManager = layoutManager
        recyclerView.addItemDecoration(DividerItemDecoration(view.context, DividerItemDecoration.VERTICAL))
        adapter = ChatsAdapter(this)
        recyclerView.adapter = adapter

        return view
    }

    private fun sendInitialRequest() {
        tryCache()
    }

    private fun tryCache() {
        // Request timeline from disk to make it quick, then replace it with timeline from
        // the server to update it
        this.timelineRepo.getStatuses(null, null, null, TimelineFragment.LOAD_AT_ONCE,
                TimelineRequestMode.DISK)
                .observeOn(AndroidSchedulers.mainThread())
                .`as`(AutoDispose.autoDisposable(AndroidLifecycleScopeProvider.from(this, Lifecycle.Event.ON_DESTROY)))
                .subscribe({ statuses: MutableList<Either<Placeholder?, Status?>?>? ->
                    filterStatuses(statuses)
                    if (statuses!!.size > 1) {
                        this.clearPlaceholdersForResponse(statuses)
                        statuses.clear()
                        statuses.addAll(statuses)
                        updateAdapter()
                        this.progressBar.visibility = View.GONE
                        // Request statuses including current top to refresh all of them
                    }
                    updateCurrent()
                    loadAbove()
                })
    }

    private fun updateCurrent() {
        if (this.statuses.isEmpty()) {
            return
        }
        val topId: String = this.statuses.first({ obj: Either<*, *> -> obj.isRight() }).asRight().id
        this.timelineRepo.getStatuses(topId, null, null, TimelineFragment.LOAD_AT_ONCE,
                TimelineRequestMode.NETWORK)
                .observeOn(AndroidSchedulers.mainThread())
                .`as`(AutoDispose.autoDisposable(AndroidLifecycleScopeProvider.from(this, Lifecycle.Event.ON_DESTROY)))
                .subscribe(
                        { statuses: MutableList<Either<Placeholder?, Status?>?>? ->
                            initialUpdateFailed = false
                            // When cached timeline is too old, we would replace it with nothing
                            if (!statuses!!.isEmpty()) {
                                filterStatuses(statuses)
                                if (!statuses.isEmpty()) {
                                    // clear old cached statuses
                                    val iterator: MutableIterator<Either<Placeholder, Status>> = statuses.iterator()
                                    while (iterator.hasNext()) {
                                        val item = iterator.next()
                                        if (item.isRight()) {
                                            val (id) = item.asRight()
                                            if (id.length < topId.length || id.compareTo(topId) < 0) {
                                                iterator.remove()
                                            }
                                        } else {
                                            val (id) = item.asLeft()
                                            if (id.length < topId.length || id.compareTo(topId) < 0) {
                                                iterator.remove()
                                            }
                                        }
                                    }
                                }
                                statuses.addAll(statuses)
                                updateAdapter()
                            }
                            this.bottomLoading = false
                        },
                        Consumer<Throwable> { e: Throwable? ->
                            initialUpdateFailed = true
                            // Indicate that we are not loading anymore
                            this.progressBar.visibility = View.GONE
                            this.swipeRefreshLayout.isRefreshing = false
                        })
    }


    private fun showNothing() {
        statusView.visibility = View.VISIBLE
        statusView.setup(R.drawable.elephant_friend_empty, R.string.message_empty, null)
    }

    private fun removeAllByAccountId(accountId: String) {
        // using iterator to safely remove items while iterating
        val iterator: MutableIterator<Either<Placeholder, Chat>> = chats.iterator()
        while (iterator.hasNext()) {
            val chat = iterator.next().asRightOrNull()
            if (chat != null &&
                    (chat.account.id == accountId || chat.account.id == accountId)) {
                iterator.remove()
            }
        }
        updateAdapter()
    }

    private fun removeAllByInstance(instance: String) {
        // using iterator to safely remove items while iterating
        val iterator: MutableIterator<Either<Placeholder, Chat>> = chats.iterator()
        while (iterator.hasNext()) {
            val chat = iterator.next().asRightOrNull()
            if (chat != null && LinkHelper.getDomain(chat.account.url) == instance) {
                iterator.remove()
            }
        }
        updateAdapter()
    }

    private fun deleteChatById(id: String) {
        for (i in chats.indices) {
            val either: Either<Placeholder, Chat> = chats.get(i)
            if (either.isRight()
                    && id == either.asRight().id) {
                chats.remove(either)
                updateAdapter()
                break
            }
        }
        if (chats.size == 0) {
            showNothing()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        /* This is delayed until onActivityCreated solely because MainActivity.composeButton isn't
         * guaranteed to be set until then. */
        /* Use a modified scroll listener that both loads more statuses as it goes, and hides
         * the follow button on down-scroll. */
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hideFab = preferences.getBoolean("fabHide", false)
        scrollListener = object : EndlessOnScrollListener(layoutManager) {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(view, dx, dy)
                val activity = activity as ActionButtonActivity?
                val composeButton = activity!!.actionButton
                if (composeButton != null) {
                    if (hideFab) {
                        if (dy > 0 && composeButton.isShown) {
                            composeButton.hide() // hides the button if we're scrolling down
                        } else if (dy < 0 && !composeButton.isShown) {
                            composeButton.show() // shows it if we are scrolling up
                        }
                    } else if (!composeButton.isShown) {
                        composeButton.show()
                    }
                }
            }

            override fun onLoadMore(totalItemsCount: Int, view: RecyclerView) {
                this@ChatsFragment.onLoadMore()
            }
        }
        recyclerView.addOnScrollListener(scrollListener)
        if (!eventRegistered) {
            eventHub.events
                    .observeOn(AndroidSchedulers.mainThread())
                    .`as`(AutoDispose.autoDisposable(AndroidLifecycleScopeProvider.from(this, Lifecycle.Event.ON_DESTROY)))
                    .subscribe { event: Event? ->
                        if (event is BlockEvent) {
                            val id = event.accountId
                            removeAllByAccountId(id)
                        } else if (event is MuteEvent) {
                            val id = event.accountId
                            removeAllByAccountId(id)
                        } else if (event is DomainMuteEvent) {
                            val instance = event.instance
                            removeAllByInstance(instance)
                        } else if (event is StatusDeletedEvent) {
                            val id = event.statusId
                            deleteChatById(id)
                        } else if (event is PreferenceChangedEvent) {
                            onPreferenceChanged(event.preferenceKey)
                        }
                    }
            eventRegistered = true
        }
    }

    private fun onPreferenceChanged(key: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        when (key) {
            "fabHide" -> {
                hideFab = sharedPreferences.getBoolean("fabHide", false)
            }
        }
    }

    override fun onRefresh() {
        if (isSwipeToRefreshEnabled) swipeRefreshLayout.isEnabled = true
        this.statusView.visibility = View.GONE
        isNeedRefresh = false
        if (this.initialUpdateFailed) {
            updateCurrent()
        }
        loadAbove()
    }

    private fun loadAbove() {
        var firstOrNull: String? = null
        var secondOrNull: String? = null
        for (i in this.statuses.indices) {
            val status: Either<Placeholder, Status> = this.statuses.get(i)
            if (status.isRight()) {
                firstOrNull = status.asRight().id
                if (i + 1 < statuses.size && statuses.get(i + 1).isRight()) {
                    secondOrNull = statuses.get(i + 1).asRight().id
                }
                break
            }
        }
        if (firstOrNull != null) {
            this.sendFetchTimelineRequest(null, firstOrNull, secondOrNull, TimelineFragment.FetchEnd.TOP, -1)
        } else {
            this.sendFetchTimelineRequest(null, null, null, TimelineFragment.FetchEnd.BOTTOM, -1)
        }
    }


    override fun onViewAccount(id: String?) {
        TODO("Not yet implemented")
    }

    private fun updateAdapter() {
        differ.submitList(chats.pairedCopy)
    }

    private fun jumpToTop() {
        if (isAdded) {
            layoutManager.scrollToPosition(0)
            recyclerView.stopScroll()
            scrollListener.reset()
        }
    }

    override fun onReselect() {
        jumpToTop()
    }

    override fun onResume() {
        super.onResume()
        startUpdateTimestamp()
    }

    override fun refreshContent() {
        if (isAdded) onRefresh() else isNeedRefresh = true
    }

    /**
     * Start to update adapter every minute to refresh timestamp
     * If setting absoluteTimeView is false
     * Auto dispose observable on pause
     */
    private fun startUpdateTimestamp() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val useAbsoluteTime = preferences.getBoolean("absoluteTimeView", false)
        if (!useAbsoluteTime) {
            Observable.interval(1, TimeUnit.MINUTES)
                    .observeOn(AndroidSchedulers.mainThread())
                    .`as`(AutoDispose.autoDisposable(AndroidLifecycleScopeProvider.from(this, Lifecycle.Event.ON_PAUSE)))
                    .subscribe { updateAdapter() }
        }
    }

}