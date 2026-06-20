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
                address = "old:17321",
                discoveryPort = 17322,
                token = "old-token",
            ),
        )
        val viewModel = newViewModel(settingsStore)
        advanceUntilIdle()

        viewModel.onSettingsAddressChange("new:17321")
        viewModel.onSettingsDiscoveryPortChange("17323")
        viewModel.onSettingsTokenChange("new-token")

        assertTrue(viewModel.uiState.value.hasUnsavedSettings)

        viewModel.saveSettings()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("new:17321", state.address)
        assertEquals("17323", state.discoveryPort)
        assertEquals("new-token", state.token)
        assertEquals("new:17321", state.settingsAddress)
        assertEquals("17323", state.settingsDiscoveryPort)
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

    private fun newViewModel(settingsRepository: TextBridgeSettingsRepository): TextBridgeViewModel {
        return TextBridgeViewModel(
            settingsStore = settingsRepository,
            discoveryClient = FakeDiscoveryClient(),
            commitClient = FakeCommitClient(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher(),
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

    override suspend fun saveAddress(address: String) {
        settingsState.value = settingsState.value.copy(address = address)
    }

    override suspend fun saveDiscoveryPort(port: Int) {
        settingsState.value = settingsState.value.copy(discoveryPort = port)
    }

    override suspend fun saveConnectionSettings(address: String, discoveryPort: Int, token: String) {
        saveConnectionSettingsCalls += 1
        settingsState.value = settingsState.value.copy(
            address = address,
            discoveryPort = discoveryPort,
            token = token,
        )
    }

    override suspend fun saveAddressAndToken(address: String, token: String) {
        settingsState.value = settingsState.value.copy(address = address, token = token)
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
    override fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        return SendResult(ok = true, message = "已发送")
    }
}
