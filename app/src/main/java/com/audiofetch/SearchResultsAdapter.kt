package com.audiofetch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.audiofetch.databinding.ItemSearchResultBinding

data class SearchResult(
    val title: String,
    val artist: String,
    val thumbnail: String,
    val url: String
)

class SearchResultsAdapter(
    private val results: List<SearchResult>,
    private val onClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]
        val b = holder.binding

        b.resultTitle.text = item.title
        b.resultArtist.text = item.artist.ifEmpty { "—" }

        b.thumbImage.load(item.thumbnail) {
            crossfade(true)
            crossfade(150)
            transformations(RoundedCornersTransformation(10f))
            placeholder(R.drawable.thumb_placeholder)
            error(R.drawable.thumb_placeholder)
        }

        b.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = results.size
}
