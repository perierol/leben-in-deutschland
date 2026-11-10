package com.nextdaymobilestudio.lebenindeutschland.feature.studysession.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nextdaymobilestudio.lebenindeutschland.R
import com.nextdaymobilestudio.lebenindeutschland.core.content.images.displayModel
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.ContentImage
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyAnswer
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyMemoryKey
import com.nextdaymobilestudio.lebenindeutschland.core.database.model.StudyQuestion
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionUiState
import com.nextdaymobilestudio.lebenindeutschland.feature.studysession.StudySessionMode
import com.nextdaymobilestudio.lebenindeutschland.ui.theme.LebenInDeutschlandTheme

@Composable
fun StudySessionScreen(
    uiState: StudySessionUiState,
    mode: StudySessionMode = StudySessionMode.ALL_QUESTIONS,
    onBack: () -> Unit,
    onAnswerSelected: (String) -> Unit,
    onSubmitAnswer: () -> Unit,
    onNextQuestion: () -> Unit,
    onRetry: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(StudySessionTestTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { StudyTopBar(mode = mode, onBack = onBack) },
        bottomBar = {
            if (uiState is StudySessionUiState.Ready) {
                StudyActionSection(
                    state = uiState,
                    onSubmitAnswer = onSubmitAnswer,
                    onNextQuestion = onNextQuestion,
                )
            }
        },
    ) { innerPadding ->
        when (uiState) {
            StudySessionUiState.Loading -> LoadingContent(innerPadding)
            StudySessionUiState.Error -> ErrorContent(innerPadding, onRetry)
            StudySessionUiState.EmptyWrongAnswers -> EmptyWrongAnswersContent(
                innerPadding = innerPadding,
                onFinished = onFinished,
            )
            is StudySessionUiState.Complete -> CompleteContent(
                state = uiState,
                mode = mode,
                innerPadding = innerPadding,
                onFinished = onFinished,
            )
            is StudySessionUiState.Ready -> QuestionContent(
                state = uiState,
                innerPadding = innerPadding,
                onAnswerSelected = onAnswerSelected,
            )
        }
    }
}

@Composable
private fun StudyTopBar(
    mode: StudySessionMode,
    onBack: () -> Unit,
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
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = stringResource(
                if (mode == StudySessionMode.WRONG_ANSWERS) {
                    R.string.study_wrong_answers_title
                } else {
                    R.string.study_title
                },
            ),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QuestionContent(
    state: StudySessionUiState.Ready,
    innerPadding: PaddingValues,
    onAnswerSelected: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var hasRevealedFeedback by rememberSaveable(state.question.id) {
        mutableStateOf(state.isSubmitted)
    }
    LaunchedEffect(state.question.id, state.isSubmitted) {
        if (state.isSubmitted && !hasRevealedFeedback) {
            hasRevealedFeedback = true
            listState.animateScrollToItem(explanationItemIndex(state.question))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .selectableGroup(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.study_progress,
                        state.currentIndex + 1,
                        state.totalCount,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                LinearProgressIndicator(
                    progress = {
                        (state.currentIndex + 1).toFloat() / state.totalCount.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                )
            }
        }

        state.question.memoryKey?.let { memoryKey ->
            item(key = "memory-key-${state.question.id}") {
                ExpandableMemoryKey(
                    questionId = state.question.id,
                    memoryKey = memoryKey,
                )
            }
        }

        state.question.questionImage?.let { image ->
            item {
                StudyImage(
                    image = image,
                    contentDescription = stringResource(
                        R.string.study_question_image_description,
                    ),
                )
            }
        }

        item {
            QuestionText(question = state.question)
        }

        items(
            items = state.question.answers,
            key = StudyAnswer::id,
        ) { answer ->
            AnswerOption(
                answer = answer,
                question = state.question,
                selectedAnswerId = state.selectedAnswerId,
                isSubmitted = state.isSubmitted,
                onSelected = { onAnswerSelected(answer.id) },
            )
        }

        if (state.isSubmitted) {
            item(key = "explanation-${state.question.id}") {
                ExplanationCard(state = state)
            }
        }
    }
}

// Mirrors the item order in QuestionContent: progress, memory key, image, question, answers.
private fun explanationItemIndex(question: StudyQuestion): Int =
    2 +
        (if (question.memoryKey != null) 1 else 0) +
        (if (question.questionImage != null) 1 else 0) +
        question.answers.size

@Composable
private fun ExpandableMemoryKey(
    questionId: String,
    memoryKey: StudyMemoryKey,
) {
    var isExpanded by rememberSaveable(questionId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(StudySessionTestTags.MEMORY_KEY_TOGGLE),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.study_memory_key_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(StudySessionTestTags.MEMORY_KEY),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(
                    text = "${memoryKey.questionCue} → ${memoryKey.answerCue}",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun QuestionText(question: StudyQuestion) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(StudySessionTestTags.QUESTION),
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
private fun AnswerOption(
    answer: StudyAnswer,
    question: StudyQuestion,
    selectedAnswerId: String?,
    isSubmitted: Boolean,
    onSelected: () -> Unit,
) {
    val isSelected = selectedAnswerId == answer.id
    val isCorrectAnswer = question.correctAnswerId == answer.id
    val containerColor = when {
        isSubmitted && isCorrectAnswer -> MaterialTheme.colorScheme.secondaryContainer
        isSubmitted && isSelected -> MaterialTheme.colorScheme.errorContainer
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isSubmitted && isCorrectAnswer -> MaterialTheme.colorScheme.onSecondaryContainer
        isSubmitted && isSelected -> MaterialTheme.colorScheme.onErrorContainer
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        isSubmitted && isCorrectAnswer -> MaterialTheme.colorScheme.secondary
        isSubmitted && isSelected -> MaterialTheme.colorScheme.error
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val resultStateDescription = when {
        isSubmitted && isCorrectAnswer -> stringResource(R.string.study_correct)
        isSubmitted && isSelected -> stringResource(R.string.study_incorrect)
        else -> null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag(StudySessionTestTags.answer(answer.id))
            .selectable(
                selected = isSelected,
                enabled = !isSubmitted,
                role = Role.RadioButton,
                onClick = onSelected,
            )
            .semantics {
                resultStateDescription?.let { stateDescription = it }
            },
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(2.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (isSelected || (isSubmitted && isCorrectAnswer)) {
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
                    StudyImage(
                        image = image,
                        contentDescription = stringResource(
                            R.string.study_answer_image_description,
                            answer.id,
                        ),
                    )
                }
            }
            if (resultStateDescription != null) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = if (isCorrectAnswer) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Close
                    },
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun StudyImage(
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
private fun ExplanationCard(state: StudySessionUiState.Ready) {
    val background = if (state.isCorrect) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (state.isCorrect) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(StudySessionTestTags.EXPLANATION),
        shape = RoundedCornerShape(20.dp),
        color = background,
        contentColor = foreground,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (state.isCorrect) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (state.isCorrect) R.string.study_correct else R.string.study_incorrect,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(R.string.study_explanation),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.question.explanation,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun StudyActionSection(
    state: StudySessionUiState.Ready,
    onSubmitAnswer: () -> Unit,
    onNextQuestion: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 4.dp,
    ) {
        Button(
            onClick = if (state.isSubmitted) onNextQuestion else onSubmitAnswer,
            enabled = !state.isSaving && (state.isSubmitted || state.selectedAnswerId != null),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .heightIn(min = 62.dp)
                .testTag(StudySessionTestTags.ACTION),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(
                    when {
                        !state.isSubmitted -> R.string.study_check_answer
                        state.isLastQuestion -> R.string.study_finish
                        else -> R.string.study_next_question
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
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
            .padding(innerPadding)
            .testTag(StudySessionTestTags.LOADING),
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
    StatusContent(
        modifier = Modifier
            .padding(innerPadding)
            .testTag(StudySessionTestTags.ERROR),
        icon = Icons.Filled.Warning,
        title = stringResource(R.string.study_error_title),
        description = stringResource(R.string.study_error_description),
        buttonText = stringResource(R.string.content_bootstrap_retry),
        buttonTestTag = StudySessionTestTags.RETRY,
        onClick = onRetry,
    )
}

@Composable
private fun CompleteContent(
    state: StudySessionUiState.Complete,
    mode: StudySessionMode,
    innerPadding: PaddingValues,
    onFinished: () -> Unit,
) {
    StatusContent(
        modifier = Modifier
            .padding(innerPadding)
            .testTag(StudySessionTestTags.COMPLETE),
        icon = Icons.Filled.CheckCircle,
        title = stringResource(
            if (mode == StudySessionMode.WRONG_ANSWERS) {
                R.string.study_wrong_answers_complete_title
            } else {
                R.string.study_complete_title
            },
        ),
        description = if (mode == StudySessionMode.WRONG_ANSWERS) {
            stringResource(
                R.string.study_wrong_answers_complete_description,
                (state.questionCount - state.correctCount).coerceAtLeast(0),
            )
        } else {
            stringResource(
                R.string.study_complete_description,
                state.correctCount,
                state.questionCount,
            )
        },
        buttonText = stringResource(R.string.study_back_home),
        buttonTestTag = StudySessionTestTags.BACK_HOME,
        onClick = onFinished,
    )
}

@Composable
private fun EmptyWrongAnswersContent(
    innerPadding: PaddingValues,
    onFinished: () -> Unit,
) {
    StatusContent(
        modifier = Modifier
            .padding(innerPadding)
            .testTag(StudySessionTestTags.EMPTY_WRONG_ANSWERS),
        icon = Icons.Filled.CheckCircle,
        title = stringResource(R.string.study_wrong_answers_empty_title),
        description = stringResource(R.string.study_wrong_answers_empty_description),
        buttonText = stringResource(R.string.study_back_home),
        buttonTestTag = StudySessionTestTags.BACK_HOME,
        onClick = onFinished,
    )
}

@Composable
private fun StatusContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    buttonText: String,
    buttonTestTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .testTag(buttonTestTag),
                ) {
                    Icon(
                        imageVector = if (icon == Icons.Filled.Warning) {
                            Icons.Filled.Refresh
                        } else {
                            Icons.Filled.CheckCircle
                        },
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StudySessionScreenPreview() {
    LebenInDeutschlandTheme {
        StudySessionScreen(
            uiState = StudySessionUiState.Ready(
                question = previewQuestion(),
                currentIndex = 0,
                totalCount = 310,
                selectedAnswerId = "A",
                isSubmitted = true,
            ),
            onBack = {},
            onAnswerSelected = {},
            onSubmitAnswer = {},
            onNextQuestion = {},
            onRetry = {},
            onFinished = {},
            mode = StudySessionMode.ALL_QUESTIONS,
        )
    }
}

private fun previewQuestion(): StudyQuestion = StudyQuestion(
    releaseId = "release-3",
    id = "general_001",
    officialNumber = 1,
    scope = "GENERAL",
    stateCode = null,
    category = null,
    correctAnswerId = "A",
    germanQuestionText = "In Deutschland dürfen Menschen offen etwas gegen die Regierung sagen, weil …",
    localizedQuestionText = "Almanya’da insanlar hükümeti açıkça eleştirebilir, çünkü …",
    explanation = "Die Meinungsfreiheit erlaubt es, die Regierung öffentlich zu kritisieren.",
    languageCode = "tr",
    questionImage = null,
    answers = listOf(
        StudyAnswer("A", 0, "hier Meinungsfreiheit gilt.", "burada ifade özgürlüğü vardır.", null),
        StudyAnswer("B", 1, "hier Religionsfreiheit gilt.", "burada din özgürlüğü vardır.", null),
        StudyAnswer("C", 2, "die Menschen Steuern zahlen.", "insanlar vergi öder.", null),
        StudyAnswer("D", 3, "die Menschen wählen dürfen.", "insanlar oy kullanabilir.", null),
    ),
    explanationStatus = "AI_GENERATED_NEEDS_REVIEW",
    reviewed = false,
    memoryKey = StudyMemoryKey(
        questionCue = "Regierung kritisieren",
        answerCue = "Meinungsfreiheit",
        status = "AI_GENERATED_NEEDS_REVIEW",
    ),
)
