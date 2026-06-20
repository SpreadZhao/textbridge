package io.github.textbridge.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class TextBridgeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun saveSettingsCommitsDraftToActiveSettings() = runTest {
        val settingsStore = FakeSettingsRepository(
            TextBridgeSettings(
                transportMode = TransportMode.LAN,
                lanAddress = "old:17321",
                discoveryPort = 17322,
                adbPort = 17321,
                token = "old-token",
            ),
        )
        val viewModel = newViewModel(settingsStore)
        advanceUntilIdle()

        viewModel.onSettingsTransportModeChange(TransportMode.ADB)
        viewModel.onSettingsLanAddressChange("new:17321")
        viewModel.onSettingsDiscoveryPortChange("17323")
        viewModel.onSettingsAdbPortChange("18000")
        viewModel.onSettingsTokenChange("new-token")

        assertTrue(viewModel.uiState.value.hasUnsavedSettings)

        viewModel.saveSettings()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(TransportMode.ADB, state.transportMode)
        assertEquals("new:17321", state.lanAddress)
        assertEquals("17323", state.discoveryPort)
        assertEquals("18000", state.adbPort)
        assertEquals("new-token", state.token)
        assertEquals(TransportMode.ADB, state.settingsTransportMode)
        assertEquals("new:17321", state.settingsLanAddress)
        assertEquals("17323", state.settingsDiscoveryPort)
        assertEquals("18000", state.settingsAdbPort)
        assertEquals("new-token", state.settingsToken)
        assertFalse(state.hasUnsavedSettings)
        assertEquals("配置已保存", state.status)
        assertEquals(1, settingsStore.saveConnectionSettingsCalls)
    }

    @Test
    fun saveSettingsRejectsInvalidDiscoveryPort() = runTest {
        val settingsStore = FakeSettingsRepository(TextBridgeSettings(discoveryPort = 17322))
        val viewModel = newViewModel(settingsStore)
        advanceUntilIdle()

        viewModel.onSettingsDiscoveryPortChange("70000")
        viewModel.saveSettings()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("17322", state.discoveryPort)
        assertEquals("70000", state.settingsDiscoveryPort)
        assertTrue(state.hasUnsavedSettings)
        assertEquals("发现端口无效", state.status)
        assertEquals(0, settingsStore.saveConnectionSettingsCalls)
    }

    @Test
    fun saveSettingsRejectsInvalidAdbPort() = runTest {
        val settingsStore = FakeSettingsRepository(TextBridgeSettings(adbPort = 17321))
        val viewModel = newViewModel(settingsStore)
        advanceUntilIdle()

        viewModel.onSettingsAdbPortChange("70000")
        viewModel.saveSettings()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("17321", state.adbPort)
        assertEquals("70000", state.settingsAdbPort)
        assertTrue(state.hasUnsavedSettings)
        assertEquals("ADB 端口无效", state.status)
        assertEquals(0, settingsStore.saveConnectionSettingsCalls)
    }

    @Test
    fun sendLanModeUsesSavedLanAddress() = runTest {
        val commitClient = FakeCommitClient()
        val viewModel = newViewModel(
            settingsRepository = FakeSettingsRepository(
                TextBridgeSettings(
                    transportMode = TransportMode.LAN,
                    lanAddress = "192.168.1.10:17321",
                    token = "token",
                ),
            ),
            commitClient = commitClient,
        )
        advanceUntilIdle()

        viewModel.onBodyChange("hello")
        viewModel.sendCurrentText()
        advanceUntilIdle()

        assertEquals(listOf("192.168.1.10:17321"), commitClient.addresses)
        assertEquals(TransportMode.LAN, viewModel.uiState.value.sendHistory.first().transportMode)
    }

    @Test
    fun sendAdbModeUsesLoopbackAddressAndAdbPort() = runTest {
        val commitClient = FakeCommitClient()
        val viewModel = newViewModel(
            settingsRepository = FakeSettingsRepository(
                TextBridgeSettings(
                    transportMode = TransportMode.ADB,
                    adbPort = 18000,
                    token = "token",
                ),
            ),
            commitClient = commitClient,
        )
        advanceUntilIdle()

        viewModel.onBodyChange("hello")
        viewModel.sendCurrentText()
        advanceUntilIdle()

        assertEquals(listOf("127.0.0.1:18000"), commitClient.addresses)
        val historyItem = viewModel.uiState.value.sendHistory.first()
        assertEquals("127.0.0.1:18000", historyItem.address)
        assertEquals(TransportMode.ADB, historyItem.transportMode)
    }

    @Test
    fun useHistoryItemFillsSendBody() = runTest {
        val viewModel = newViewModel(FakeSettingsRepository(TextBridgeSettings()))
        advanceUntilIdle()
        val item = SendHistoryItem(
            id = "history-id",
            text = "from history",
            sentAtMillis = 1710000000000L,
            address = "192.168.1.10:17321",
        )

        viewModel.useHistoryItem(item)

        assertEquals("from history", viewModel.uiState.value.body)
        assertEquals("已从历史填入", viewModel.uiState.value.status)
    }

    private fun newViewModel(
        settingsRepository: TextBridgeSettingsRepository,
        commitClient: TextBridgeCommitClient = FakeCommitClient(),
        ioDispatcher: TestDispatcher = mainDispatcherRule.testDispatcher,
    ): TextBridgeViewModel {
        return TextBridgeViewModel(
            settingsStore = settingsRepository,
            discoveryClient = FakeDiscoveryClient(),
            commitClient = commitClient,
            ioDispatcher = ioDispatcher,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakeSettingsRepository(
    initialSettings: TextBridgeSettings,
) : TextBridgeSettingsRepository {
    private val settingsState = MutableStateFlow(initialSettings)
    override val settings: StateFlow<TextBridgeSettings> = settingsState
    var saveConnectionSettingsCalls = 0
        private set

    override suspend fun saveConnectionSettings(
        transportMode: TransportMode,
        lanAddress: String,
        discoveryPort: Int,
        adbPort: Int,
        token: String,
    ) {
        saveConnectionSettingsCalls += 1
        settingsState.value = settingsState.value.copy(
            transportMode = transportMode,
            lanAddress = lanAddress,
            discoveryPort = discoveryPort,
            adbPort = adbPort,
            token = token,
        )
    }

    override suspend fun addSendHistoryItem(item: SendHistoryItem): List<SendHistoryItem> {
        val updated = SendHistoryCodec.prepend(settingsState.value.sendHistory, item)
        settingsState.value = settingsState.value.copy(sendHistory = updated)
        return updated
    }

    override suspend fun removeSendHistoryItem(itemId: String): List<SendHistoryItem> {
        val updated = SendHistoryCodec.remove(settingsState.value.sendHistory, itemId)
        settingsState.value = settingsState.value.copy(sendHistory = updated)
        return updated
    }

    override suspend fun clearSendHistory() {
        settingsState.value = settingsState.value.copy(sendHistory = emptyList())
    }
}

private class FakeDiscoveryClient : TextBridgeDiscoveryClient {
    override fun discover(discoveryPort: Int): List<DiscoveryOffer> = emptyList()
}

private class FakeCommitClient : TextBridgeCommitClient {
    val addresses = mutableListOf<String>()

    override fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        addresses += address
        return SendResult(ok = true, message = "已发送")
    }
}
