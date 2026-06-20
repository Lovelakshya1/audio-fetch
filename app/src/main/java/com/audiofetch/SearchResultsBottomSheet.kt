package com.audiofetch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.audiofetch.databinding.BottomSheetResultsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SearchResultsBottomSheet(
    private val results: List<SearchResult>,
    private val onSelect: (SearchResult) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetResultsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = SearchResultsAdapter(results) { selected ->
            dismiss()
            onSelect(selected)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
