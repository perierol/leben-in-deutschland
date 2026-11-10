package com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nextdaymobilestudio.lebenindeutschland.R
import com.nextdaymobilestudio.lebenindeutschland.core.content.images.displayModel
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentImage
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.MockExamThresholds
import com.nextdaymobilestudio.lebenindeutschland.feature.mockexam.MockExamUiState
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme
import java.util.Locale

@Composable
fun MockExamScreen(
    uiState: MockExamUiState,
    onBack: () -> Unit,
    onStartExam: () -> Unit,
    onAnswerSelected: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRequestSubmit: () -> Unit,
    onCancelSubmit: () -> Unit,
    onConfirmSubmit: () -> Unit,
    onRetry: () -> Unit,
    onStartNewExam: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(MockExamTestTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MockExamTopBar(
                uiState = uiState,
                onBack = onBack,
                onRequestSubmit = onRequestSubmit,
            )
        },
        bottomBar = {
            if (uiState is MockExamUiState.Ready) {
                ExamNavigation(
                    state = uiState,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onRequestSubmit = onRequestSubmit,
                )
            }
        },
    ) { innerPadding ->
        when (uiState) {
            MockExamUiState.Loading -> LoadingContent(innerPadding)
            MockExamUiState.Setup -> SetupContent(innerPadding, onStartExam)
            MockExamUiState.Error -> ErrorContent(innerPadding, onRetry)
            is MockExamUiState.Ready -> QuestionContent(
                state = uiState,
                innerPadding = innerPadding,
                onAnswerSelected = onAnswerSelected,
            )
            is MockExamUiState.Result -> ResultContent(
                state = uiState,
                innerPadding = innerPadding,
                onStartNewExam = onStartNewExam,
                onFinished = onFinished,
            )
        }
    }

    val readyState = uiState as? MockExamUiState.Ready
    if (readyState?.showSubmitConfirmation == true) {
        SubmitConfirmationDialog(
            state = readyState,
            onCancel = onCancelSubmit,
            onConfirm = onConfirmSubmit,
        )
    }
}

@Composable
private fun MockExamTopBar(
    uiState: MockExamUiState,
    onBack: () -> Unit,
    onRequestSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.state_selection_back),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.mock_exam_title),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (uiState is MockExamUiState.Ready) {
            TextButton(
                onClick = onRequestSubmit,
                enabled = !uiState.isSaving,
                modifier = Modifier.testTag(MockExamTestTags.FINISH),
            ) {
                Text(stringResource(R.string.mock_exam_finish))
            }
        }
    }
}

@Composable
private fun SetupContent(
    innerPadding: PaddingValues,
    onStartExam: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag(MockExamTestTags.SETUP),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.mock_exam_setup_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.mock_exam_setup_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        item {
            ExamStartCard(onClick = onStartExam)
        }
    }
}

@Composable
private fun ExamStartCard(
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mock_exam_lid_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.mock_exam_result_threshold,
                    MockExamThresholds.LIFE_IN_GERMANY,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.mock_exam_naturalization_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.mock_exam_result_threshold,
                    MockExamThresholds.NATURALIZATION,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .testTag(MockExamTestTags.START),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.mock_exam_start),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QuestionContent(
    state: MockExamUiState.Ready,
    innerPadding: PaddingValues,
    onAnswerSelected: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .selectableGroup(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.mock_exam_progress,
                        state.currentIndex + 1,
                        state.totalCount,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                TimerPill(state.remainingSeconds)
            }
        }
        item {
            LinearProgressIndicator(
                progress = { (state.currentIndex + 1).toFloat() / state.totalCount },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )
        }
        item {
            Text(
                text = stringResource(
                    R.string.mock_exam_answered,
                    state.answeredCount,
                    state.totalCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.question.questionImage?.let { image ->
            item { ExamImage(image, stringResource(R.string.study_question_image_description)) }
        }
        item { ExamQuestionText(state.question) }
        items(state.question.answers, key = StudyAnswer::id) { answer ->
            ExamAnswerOption(
                answer = answer,
                selected = state.selectedAnswerId == answer.id,
                enabled = !state.isSaving,
                onSelected = { onAnswerSelected(answer.id) },
            )
        }
    }
}

@Composable
private fun TimerPill(remainingSeconds: Long) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val text = "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    Surface(
        modifier = Modifier.testTag(MockExamTestTags.TIMER),
        shape = CircleShape,
        color = if (remainingSeconds <= 5 * 60) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExamQuestionText(question: StudyQuestion) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MockExamTestTags.QUESTION),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = question.germanQuestionText,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (question.localizedQuestionText != question.germanQuestionText) {
                Text(
                    text = question.localizedQuestionText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ExamAnswerOption(
    answer: StudyAnswer,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MockExamTestTags.answer(answer.id))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelected,
            ),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = 2.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (selected) {
                    contentColor.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = answer.id,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = answer.germanText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (answer.localizedText != answer.germanText) {
                    Text(
                        text = answer.localizedText,
                        color = contentColor.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                answer.image?.let { image ->
                    ExamImage(
                        image = image,
                        contentDescription = stringResource(
                            R.string.study_answer_image_description,
                            answer.id,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamImage(
    image: ContentImage,
    contentDescription: String,
) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(image) { image.displayModel(context) },
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp, max = 320.dp)
            .clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ExamNavigation(
    state: MockExamUiState.Ready,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRequestSubmit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = !state.isFirstQuestion && !state.isSaving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp)
                    .testTag(MockExamTestTags.PREVIOUS),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.mock_exam_previous))
            }
            Button(
                onClick = if (state.isLastQuestion) onRequestSubmit else onNext,
                enabled = !state.isSaving,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp)
                    .testTag(MockExamTestTags.NEXT),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.isLastQuestion) {
                            R.string.mock_exam_finish
                        } else {
                            R.string.mock_exam_next
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SubmitConfirmationDialog(
    state: MockExamUiState.Ready,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(MockExamTestTags.CONFIRM_DIALOG),
        title = { Text(stringResource(R.string.mock_exam_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.mock_exam_confirm_description,
                    state.answeredCount,
                    state.unansweredCount,
                ),
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.mock_exam_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.mock_exam_submit))
            }
        },
    )
}

@Composable
private fun ResultContent(
    state: MockExamUiState.Result,
    innerPadding: PaddingValues,
    onStartNewExam: () -> Unit,
    onFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .testTag(MockExamTestTags.RESULT),
            shape = RoundedCornerShape(24.dp),
            color = if (state.lifeInGermanyPassed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = if (state.lifeInGermanyPassed) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    text = stringResource(
                        if (state.lifeInGermanyPassed) {
                            R.string.mock_exam_passed_title
                        } else {
                            R.string.mock_exam_failed_title
                        },
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (state.submittedByTimeout) {
                    Text(
                        text = stringResource(R.string.mock_exam_time_up),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.mock_exam_result_score,
                        state.correctCount,
                        state.totalCount,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.mock_exam_result_breakdown,
                        state.correctCount,
                        state.wrongCount,
                        state.unansweredCount,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                ResultStatusRow(
                    label = stringResource(R.string.mock_exam_lid_title),
                    passed = state.lifeInGermanyPassed,
                )
                ResultStatusRow(
                    label = stringResource(R.string.mock_exam_naturalization_title),
                    passed = state.naturalizationPassed,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onStartNewExam,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.mock_exam_new))
                }
                FilledTonalButton(
                    onClick = onFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.mock_exam_back_home))
                }
            }
        }
    }
}

@Composable
private fun ResultStatusRow(
    label: String,
    passed: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(
                    if (passed) {
                        R.string.mock_exam_passed_title
                    } else {
                        R.string.mock_exam_failed_title
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LoadingContent(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun ErrorContent(
    innerPadding: PaddingValues,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mock_exam_error_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.mock_exam_error_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.mock_exam_retry))
            }
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MockExamSetupPreview() {
    LebenInDeutschlandTheme {
        MockExamScreen(
            uiState = MockExamUiState.Setup,
            onBack = {},
            onStartExam = {},
            onAnswerSelected = {},
            onPrevious = {},
            onNext = {},
            onRequestSubmit = {},
            onCancelSubmit = {},
            onConfirmSubmit = {},
            onRetry = {},
            onStartNewExam = {},
            onFinished = {},
        )
    }
}
