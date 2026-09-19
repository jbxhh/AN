package io.anonero.ui.onboard

import io.anonero.ui.theme.AnonNeroTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.anonero.R
import io.anonero.ui.onboard.graph.OnboardLoading


@Composable
fun OnboardLoadingComposable(
    modifier: Modifier = Modifier,
    loadingState: OnboardLoading = OnboardLoading(
        message = stringResource(R.string.loading)
    )
) {

    Card(
        modifier = modifier.size(width = 300.dp, height = 250.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                strokeCap = StrokeCap.Round,
                modifier = Modifier.size(200.dp),

                )
            Text(loadingState.message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview(device = "id:pixel_8")
@Composable
private fun OnboardLoadingPrev() {
    AnonNeroTheme {
        OnboardLoadingComposable()
    }
}