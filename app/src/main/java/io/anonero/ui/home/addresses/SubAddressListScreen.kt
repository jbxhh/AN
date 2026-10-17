package io.anonero.ui.home.addresses

import io.anonero.ui.theme.AnonNeroTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.anonero.R
import io.anonero.model.Subaddress
import io.anonero.services.WalletState
import io.anonero.ui.components.SubAddressLabelDialog
import io.anonero.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject


class SubAddressListViewModel : ViewModel() {

    private val walletState: WalletState by inject(WalletState::class.java)

    val subAddresses = walletState.subAddresses.asLiveData()
    private val _addAddressMessage = MutableStateFlow<String?>(null)
    val addAddressMessage = _addAddressMessage.asLiveData()
    private val _isAddingAddress = MutableStateFlow(false)
    val isAddingAddress = _isAddingAddress.asLiveData()

    fun getNextAddress() {
        if (_isAddingAddress.value) return
        viewModelScope.launch {
            _isAddingAddress.value = true
            try {
                val address = withContext(Dispatchers.IO) {
                    walletState.getNewAddress()
                }
                _addAddressMessage.value = "已添加子地址 #" + address.addressIndex
            } catch (e: Exception) {
                _addAddressMessage.value =
                    e.message?.takeIf(String::isNotBlank) ?: "添加子地址失败"
            } finally {
                _isAddingAddress.value = false
            }
        }
    }

    fun clearAddAddressMessage() {
        _addAddressMessage.value = null
    }

    fun updateAddressLabel(label: String, addressIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            walletState.updateAddressLabel(label, addressIndex)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SubAddressesScreen(
    modifier: Modifier = Modifier,
    onBackPress: () -> Unit = {},
    navigateToDetails: (Subaddress) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    BackHandler {
        onBackPress()
    }
    val vm = viewModel<SubAddressListViewModel>()
    val addresses by vm.subAddresses.observeAsState(listOf())
    val addAddressMessage by vm.addAddressMessage.observeAsState()
    val isAddingAddress by vm.isAddingAddress.observeAsState(false)
    val snackbarHostState = remember { SnackbarHostState() }
    var editingAddress by remember { mutableStateOf<Subaddress?>(null) }

    LaunchedEffect(addAddressMessage) {
        val message = addAddressMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.clearAddAddressMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {},
                actions = {
                    IconButton(
                        onClick = { vm.getNextAddress() },
                        enabled = !isAddingAddress
                    ) {
                        if (isAddingAddress) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(2.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.add_subaddress_label)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            items(addresses.size) {
                val address = addresses[it]
                with(sharedTransitionScope) {
                    ListItem(
                        modifier = Modifier
                            .sharedElement(
                                sharedTransitionScope.rememberSharedContentState(key = "${address.address}:${address.addressIndex}"),
                                animatedVisibilityScope = animatedContentScope
                            )
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            .clickable { navigateToDetails(address) },
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val isPrimaryAddressLabel = listOf(address.label, address.displayLabel).any { label ->
                                    label.trim().replace(Regex("\\s+"), " ")
                                        .startsWith("Primary address", ignoreCase = true)
                                }
                                val generatedLabel =
                                    address.label.isBlank() ||
                                    Subaddress.DEFAULT_LABEL_FORMATTER.matcher(address.label).matches() ||
                                    address.displayLabel.startsWith("SubAddress #", ignoreCase = true) ||
                                    isPrimaryAddressLabel
                                val labelText = when {
                                    generatedLabel && address.addressIndex == 0 ->
                                        stringResource(R.string.primary_address)
                                    generatedLabel ->
                                        stringResource(R.string.subaddress_default_label, address.addressIndex)
                                    else -> address.displayLabel
                                }
                                Text(
                                    labelText,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { editingAddress = address }
                                )
                                Text(
                                    Formats.getDisplayAmount(address.totalAmount),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    address.squashedAddress,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Justify
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    editingAddress?.let { addr ->
        val isPrimaryAddressLabel =
            addr.label.trim().equals("Primary address", ignoreCase = true) ||
            addr.displayLabel.trim().equals("Primary address", ignoreCase = true)
        SubAddressLabelDialog(
            label = if (isPrimaryAddressLabel) "" else addr.displayLabel,
            onSave = { label ->
                vm.updateAddressLabel(label, addr.addressIndex)
                editingAddress = null
            },
            onCancel = { editingAddress = null }
        )
    }
}

@Preview(device = "id:pixel_7_pro")
@Composable
private fun SubAddressScreenPrev() {
    AnonNeroTheme { }
}
