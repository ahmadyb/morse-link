package com.morselink.feature.dashboard

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import android.net.Uri
import androidx.navigation.fragment.findNavController
import com.morselink.core.ui.DeviceTier
import com.morselink.core.ui.RadarView
import com.morselink.feature.dashboard.databinding.FragmentDashboardBinding
import dagger.hilt.android.AndroidEntryPoint

/** §14.1 — identity, radar, two actions. Nothing else. */
@AndroidEntryPoint
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    companion object {
        const val NAV_SEND = "morselink://send"
        const val NAV_RECEIVE = "morselink://receive"
        const val NAV_WEBSHARE = "morselink://webshare"
    }

    private val viewModel: DashboardViewModel by viewModels()

    private var binding: FragmentDashboardBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentDashboardBinding.bind(view)
        this.binding = binding

        viewModel.deviceName.observe(viewLifecycleOwner) { name ->
            binding.deviceName.text = name
            binding.avatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "M"
        }

        // §7 — no sweep animation on low-end devices, just the static rings.
        val lowEnd = DeviceTier.isLowEnd(requireContext())
        binding.radar.setAnimated(!lowEnd)
        if (lowEnd) binding.radar.setBlips(emptyList())

        viewModel.peers.observe(viewLifecycleOwner) { peers ->
            binding.radar.setBlips(peers.mapIndexed { index, _ -> RadarView.blipFor(index) })
            binding.radarStatus.text = if (peers.isEmpty()) {
                getString(R.string.dashboard_searching)
            } else {
                resources.getQuantityString(R.plurals.dashboard_peers_found, peers.size, peers.size)
            }
        }

        binding.btnSend.setOnClickListener {
            findNavController().navigate(Uri.parse(NAV_SEND))
        }
        binding.btnReceive.setOnClickListener {
            findNavController().navigate(Uri.parse(NAV_RECEIVE))
        }
        binding.btnWebshare.setOnClickListener {
            findNavController().navigate(Uri.parse(NAV_WEBSHARE))
        }
    }

    override fun onResume() {
        super.onResume()
        binding?.radar?.start()
        viewModel.startDiscovery()
    }

    override fun onPause() {
        viewModel.stopDiscovery()
        super.onPause()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
