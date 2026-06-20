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
    fun sendModeDefaultsToSendOnly() = runTest {
        val viewModel = newViewModel(FakeSettingsRepository(TextBridgeSettings()))
        advanceUntilIdle()

        assertEquals(SendMode.SEND_ONLY, viewModel.uiState.value.sendMode)
    }

    @Test
    fun sendModeChangePersistsPreference() = runTest {
        val settingsStore = FakeSettingsRepository(TextBridgeSettings())
        val viewModel = newViewModel(settingsStore)
        advanceUntilIdle()

        viewModel.onSendModeChange(SendMode.SEND_THEN_ENTER)
        advanceUntilIdle()

        assertEquals(SendMode.SEND_THEN_ENTER, viewModel.uiState.value.sendMode)
        assertEquals(SendMode.SEND_THEN_ENTER, settingsStore.settings.value.sendMode)
        assertEquals(1, settingsStore.saveSendModeCalls)
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
        assertTrue(commitClient.keyActions.isEmpty())
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
    fun sendThenEnterUsesSameAdbEndpointAfterSuccessfulCommit() = runTest {
        val commitClient = FakeCommitClient()
        val viewModel = newViewModel(
            settingsRepository = FakeSettingsRepository(
                TextBridgeSettings(
                    transportMode = TransportMode.ADB,
                    adbPort = 18000,
                    token = "token",
                    sendMode = SendMode.SEND_THEN_ENTER,
                ),
            ),
            commitClient = commitClient,
        )
        advanceUntilIdle()

        viewModel.onBodyChange("hello")
        viewModel.sendCurrentText()
        advanceUntilIdle()

        assertEquals(listOf("commit:127.0.0.1:18000", "key:127.0.0.1:18000"), commitClient.events)
        assertEquals(RemoteKey.RETURN, commitClient.keyActions.first().key)
        assertTrue(commitClient.keyActions.first().modifiers.isEmpty())
        assertEquals("", viewModel.uiState.value.body)
        assertEquals("hello", viewModel.uiState.value.sendHistory.first().text)
        assertEquals("已发送并按 Enter", viewModel.uiState.value.status)
    }

    @Test
    fun sendThenEnterDoesNotPressEnterWhenCommitFails() = runTest {
        val commitClient = FakeCommitClient().apply {
            commitResult = SendResult(ok = false, message = "请求无效")
        }
        val viewModel = newViewModel(
            settingsRepository = FakeSettingsRepository(
                TextBridgeSettings(
                    transportMode = TransportMode.LAN,
                    lanAddress = "192.168.1.10:17321",
                    token = "token",
                    sendMode = SendMode.SEND_THEN_ENTER,
                ),
            ),
            commitClient = commitClient,
        )
        advanceUntilIdle()

        viewModel.onBodyChange("hello")
        viewModel.sendCurrentText()
        advanceUntilIdle()

        assertEquals(listOf("commit:192.168.1.10:17321"), commitClient.events)
        assertTrue(commitClient.keyActions.isEmpty())
        assertEquals("hello", viewModel.uiState.value.body)
        assertTrue(viewModel.uiState.value.sendHistory.isEmpty())
        assertEquals("请求无效", viewModel.uiState.value.status)
    }

    @Test
    fun sendThenEnterRecordsHistoryWhenEnterFailsAfterCommit() = runTest {
        val commitClient = FakeCommitClient().apply {
            keyResult = SendResult(ok = false, message = "电脑没有输入焦点")
        }
        val viewModel = newViewModel(
            settingsRepository = FakeSettingsRepository(
                TextBridgeSettings(
                    transportMode = TransportMode.LAN,
                    lanAddress = "192.168.1.10:17321",
                    token = "token",
                    sendMode = SendMode.SEND_THEN_ENTER,
                ),
            ),
            commitClient = commitClient,
        )
        advanceUntilIdle()

        viewModel.onBodyChange("hello")
        viewModel.sendCurrentText()
        advanceUntilIdle()

        assertEquals(listOf("commit:192.168.1.10:17321", "key:192.168.1.10:17321"), commitClient.events)
        assertEquals("", viewModel.uiState.value.body)
        assertEquals("hello", viewModel.uiState.value.sendHistory.first().text)
        assertEquals("文字已发送，但 Enter 失败：电脑没有输入焦点", viewModel.uiState.value.status)
    }

    @Test
    fun sendKeyAdbModeUsesLoopbackAddressAndDoesNotTouchTextHistory() = runTest {
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

        viewModel.onBodyChange("keep me")
        viewModel.sendKeyAction(RemoteKey.RETURN)
        advanceUntilIdle()

        assertEquals(listOf("127.0.0.1:18000"), commitClient.keyAddresses)
        assertEquals(RemoteKey.RETURN, commitClient.keyActions.first().key)
        assertTrue(commitClient.keyActions.first().modifiers.isEmpty())
        assertEquals("keep me", viewModel.uiState.value.body)
        assertTrue(viewModel.uiState.value.sendHistory.isEmpty())
        assertEquals("按键已发送", viewModel.uiState.value.status)
    }

    @Test
    fun sendKeyConsumesOneShotModifier() = runTest {
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

        viewModel.toggleKeyModifier(KeyModifier.CONTROL)
        assertEquals(setOf(KeyModifier.CONTROL), viewModel.uiState.value.selectedKeyModifiers)

        viewModel.sendKeyAction(RemoteKey.RETURN)
        advanceUntilIdle()

        assertEquals(listOf(KeyModifier.CONTROL), commitClient.keyActions.first().modifiers)
        assertTrue(viewModel.uiState.value.selectedKeyModifiers.isEmpty())
    }

    @Test
    fun sendShortcutAddsFixedControlModifier() = runTest {
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

        viewModel.sendKeyAction(RemoteKey.V, setOf(KeyModifier.CONTROL))
        advanceUntilIdle()

        assertEquals(RemoteKey.V, commitClient.keyActions.first().key)
        assertEquals(listOf(KeyModifier.CONTROL), commitClient.keyActions.first().modifiers)
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
    var saveSendModeCalls = 0
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

    override suspend fun saveSendMode(sendMode: SendMode) {
        saveSendModeCalls += 1
        settingsState.value = settingsState.value.copy(sendMode = sendMode)
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
    val keyAddresses = mutableListOf<String>()
    val keyActions = mutableListOf<KeyAction>()
    val events = mutableListOf<String>()
    var commitResult = SendResult(ok = true, message = "已发送")
    var keyResult = SendResult(ok = true, message = "按键已发送")

    override fun postCommit(address: String, token: String, requestId: String, text: String): SendResult {
        addresses += address
        events += "commit:$address"
        return commitResult
    }

    override fun postKey(
        address: String,
        token: String,
        requestId: String,
        key: RemoteKey,
        modifiers: List<KeyModifier>,
    ): SendResult {
        keyAddresses += address
        keyActions += KeyAction(key = key, modifiers = modifiers)
        events += "key:$address"
        return keyResult
    }
}

private data class KeyAction(
    val key: RemoteKey,
    val modifiers: List<KeyModifier>,
)
