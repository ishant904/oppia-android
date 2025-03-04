package org.oppia.android.app.topic.questionplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.Transformations
import nl.dionsegijn.konfetti.KonfettiView
import org.oppia.android.app.fragment.FragmentScope
import org.oppia.android.app.model.AnsweredQuestionOutcome
import org.oppia.android.app.model.EphemeralQuestion
import org.oppia.android.app.model.EphemeralState
import org.oppia.android.app.model.EventLog
import org.oppia.android.app.model.HelpIndex
import org.oppia.android.app.model.ProfileId
import org.oppia.android.app.model.State
import org.oppia.android.app.model.UserAnswer
import org.oppia.android.app.player.state.ConfettiConfig.MINI_CONFETTI_BURST
import org.oppia.android.app.player.state.StatePlayerRecyclerViewAssembler
import org.oppia.android.app.player.state.listener.RouteToHintsAndSolutionListener
import org.oppia.android.app.player.stopplaying.RestartPlayingSessionListener
import org.oppia.android.app.player.stopplaying.StopStatePlayingSessionListener
import org.oppia.android.app.topic.conceptcard.ConceptCardFragment.Companion.CONCEPT_CARD_DIALOG_FRAGMENT_TAG
import org.oppia.android.app.utility.SplitScreenManager
import org.oppia.android.app.viewmodel.ViewModelProvider
import org.oppia.android.databinding.QuestionPlayerFragmentBinding
import org.oppia.android.domain.oppialogger.OppiaLogger
import org.oppia.android.domain.question.QuestionAssessmentProgressController
import org.oppia.android.util.data.AsyncResult
import org.oppia.android.util.data.DataProviders.Companion.toLiveData
import org.oppia.android.util.gcsresource.QuestionResourceBucketName
import org.oppia.android.util.system.OppiaClock
import javax.inject.Inject

/** The presenter for [QuestionPlayerFragment]. */
@FragmentScope
class QuestionPlayerFragmentPresenter @Inject constructor(
  private val activity: AppCompatActivity,
  private val fragment: Fragment,
  private val context: Context,
  private val viewModelProvider: ViewModelProvider<QuestionPlayerViewModel>,
  private val questionAssessmentProgressController: QuestionAssessmentProgressController,
  private val oppiaClock: OppiaClock,
  private val oppiaLogger: OppiaLogger,
  @QuestionResourceBucketName private val resourceBucketName: String,
  private val assemblerBuilderFactory: StatePlayerRecyclerViewAssembler.Builder.Factory,
  private val splitScreenManager: SplitScreenManager
) {
  // TODO(#503): Add tests for the question player.

  private val routeToHintsAndSolutionListener = activity as RouteToHintsAndSolutionListener
  private val hasConversationView = false

  private val questionViewModel by lazy { getQuestionPlayerViewModel() }
  private val ephemeralQuestionLiveData: LiveData<AsyncResult<EphemeralQuestion>> by lazy {
    questionAssessmentProgressController.getCurrentQuestion().toLiveData()
  }

  private lateinit var binding: QuestionPlayerFragmentBinding
  private lateinit var recyclerViewAssembler: StatePlayerRecyclerViewAssembler
  private lateinit var questionId: String
  private lateinit var currentQuestionState: State
  private lateinit var helpIndex: HelpIndex

  fun handleCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    profileId: ProfileId
  ): View? {
    binding = QuestionPlayerFragmentBinding.inflate(
      inflater,
      container,
      /* attachToRoot= */ false
    )

    recyclerViewAssembler = createRecyclerViewAssembler(
      assemblerBuilderFactory.create(resourceBucketName, "skill", profileId),
      binding.congratulationsTextView,
      binding.congratulationsTextConfettiView
    )

    binding.apply {
      lifecycleOwner = fragment
      viewModel = questionViewModel
    }
    binding.questionRecyclerView.apply {
      adapter = recyclerViewAssembler.adapter
    }
    binding.extraInteractionRecyclerView.apply {
      adapter = recyclerViewAssembler.rhsAdapter
    }

    binding.hintsAndSolutionFragmentContainer.setOnClickListener {
      routeToHintsAndSolutionListener.routeToHintsAndSolution(
        questionId,
        helpIndex
      )
    }
    subscribeToCurrentQuestion()
    return binding.root
  }

  fun revealHint(hintIndex: Int) {
    subscribeToHintSolution(questionAssessmentProgressController.submitHintIsRevealed(hintIndex))
  }

  fun revealSolution() {
    subscribeToHintSolution(questionAssessmentProgressController.submitSolutionIsRevealed())
  }

  fun dismissConceptCard() {
    fragment.childFragmentManager.findFragmentByTag(
      CONCEPT_CARD_DIALOG_FRAGMENT_TAG
    )?.let { dialogFragment ->
      fragment.childFragmentManager.beginTransaction().remove(dialogFragment).commitNow()
    }
  }

  fun handleAnswerReadyForSubmission(answer: UserAnswer) {
    // An interaction has indicated that an answer is ready for submission.
    handleSubmitAnswer(answer)
  }

  fun onContinueButtonClicked() {
    questionViewModel.setHintBulbVisibility(false)
    hideKeyboard()
    moveToNextState()
  }

  fun onNextButtonClicked() = moveToNextState()

  fun onReplayButtonClicked() {
    hideKeyboard()
    (activity as RestartPlayingSessionListener).restartSession()
  }

  fun onReturnToTopicButtonClicked() {
    hideKeyboard()
    (activity as StopStatePlayingSessionListener).stopSession()
  }

  fun onSubmitButtonClicked() {
    hideKeyboard()
    handleSubmitAnswer(
      questionViewModel.getPendingAnswer(
        recyclerViewAssembler::getPendingAnswerHandler
      )
    )
  }

  fun onResponsesHeaderClicked() {
    recyclerViewAssembler.togglePreviousAnswers(questionViewModel.itemList)
    recyclerViewAssembler.adapter.notifyDataSetChanged()
  }

  /**
   * Updates whether the submit button should be active based on whether the pending answer is in an
   * error state.
   */
  fun updateSubmitButton(pendingAnswerError: String?, inputAnswerAvailable: Boolean) {
    if (inputAnswerAvailable) {
      questionViewModel.setCanSubmitAnswer(pendingAnswerError == null)
    } else {
      questionViewModel.setCanSubmitAnswer(canSubmitAnswer = false)
    }
  }

  fun handleKeyboardAction() = onSubmitButtonClicked()

  fun onHintAvailable(helpIndex: HelpIndex, isCurrentStatePendingState: Boolean) {
    this.helpIndex = helpIndex
    showHintsAndSolutions(helpIndex, isCurrentStatePendingState)
  }

  private fun subscribeToCurrentQuestion() {
    ephemeralQuestionLiveData.observe(
      fragment,
      Observer {
        processEphemeralQuestionResult(it)
      }
    )
  }

  private fun processEphemeralQuestionResult(result: AsyncResult<EphemeralQuestion>) {
    if (result.isFailure()) {
      oppiaLogger.e(
        "QuestionPlayerFragment",
        "Failed to retrieve ephemeral question",
        result.getErrorOrNull()!!
      )
    } else if (result.isPending()) {
      // Display nothing until a valid result is available.
      return
    }
    val ephemeralQuestion = result.getOrThrow()
    // TODO(#497): Update this to properly link to question assets.
    val skillId = ephemeralQuestion.question.linkedSkillIdsList.firstOrNull() ?: ""

    questionId = ephemeralQuestion.question.questionId

    updateProgress(ephemeralQuestion.currentQuestionIndex, ephemeralQuestion.totalQuestionCount)
    logQuestionPlayerEvent(
      ephemeralQuestion.question.questionId,
      ephemeralQuestion.question.linkedSkillIdsList
    )
    updateEndSessionMessage(ephemeralQuestion.ephemeralState)

    currentQuestionState = ephemeralQuestion.ephemeralState.state

    val isSplitView =
      splitScreenManager.shouldSplitScreen(ephemeralQuestion.ephemeralState.state.interaction.id)

    if (isSplitView) {
      questionViewModel.isSplitView.set(true)
      questionViewModel.centerGuidelinePercentage.set(0.5f)
    } else {
      questionViewModel.isSplitView.set(false)
      questionViewModel.centerGuidelinePercentage.set(1f)
    }

    val dataPair = recyclerViewAssembler.compute(
      ephemeralQuestion.ephemeralState,
      skillId,
      isSplitView
    )

    questionViewModel.itemList.clear()
    questionViewModel.itemList += dataPair.first
    questionViewModel.rightItemList.clear()
    questionViewModel.rightItemList += dataPair.second
  }

  private fun updateProgress(currentQuestionIndex: Int, questionCount: Int) {
    questionViewModel.updateQuestionProgress(
      currentQuestion = currentQuestionIndex + 1,
      questionCount = questionCount,
      progressPercentage = (((currentQuestionIndex + 1) / questionCount.toDouble()) * 100).toInt(),
      isAtEndOfSession = currentQuestionIndex == questionCount
    )
  }

  private fun updateEndSessionMessage(ephemeralState: EphemeralState) {
    val isStateTerminal =
      ephemeralState.stateTypeCase == EphemeralState.StateTypeCase.TERMINAL_STATE
    val endSessionViewsVisibility = if (isStateTerminal) View.VISIBLE else View.GONE
    binding.endSessionHeaderTextView.visibility = endSessionViewsVisibility
    binding.endSessionBodyTextView.visibility = endSessionViewsVisibility
  }

  private fun handleSubmitAnswer(answer: UserAnswer) {
    subscribeToAnswerOutcome(questionAssessmentProgressController.submitAnswer(answer))
  }

  /** This function listens to and processes the result of submitAnswer from QuestionAssessmentProgressController. */
  private fun subscribeToAnswerOutcome(
    answerOutcomeResultLiveData: LiveData<AsyncResult<AnsweredQuestionOutcome>>
  ) {
    val answerOutcomeLiveData =
      Transformations.map(answerOutcomeResultLiveData, ::processAnsweredQuestionOutcome)
    answerOutcomeLiveData.observe(
      fragment,
      Observer<AnsweredQuestionOutcome> { result ->
        recyclerViewAssembler.isCorrectAnswer.set(result.isCorrectAnswer)
        if (result.isCorrectAnswer) {
          questionViewModel.setHintBulbVisibility(false)
          recyclerViewAssembler.showCelebrationOnCorrectAnswer(result.feedback)
        } else {
          questionViewModel.setCanSubmitAnswer(canSubmitAnswer = false)
        }
      }
    )
  }

  /** Subscribes to the result of requesting to show a hint or solution. */
  private fun subscribeToHintSolution(resultLiveData: LiveData<AsyncResult<Any?>>) {
    resultLiveData.observe(
      fragment,
      { result ->
        if (result.isFailure()) {
          oppiaLogger.e(
            "StateFragment", "Failed to retrieve hint/solution", result.getErrorOrNull()!!
          )
        } else {
          // If the hint/solution, was revealed remove dot and radar.
          questionViewModel.setHintOpenedAndUnRevealedVisibility(false)
        }
      }
    )
  }

  /** Helper for subscribeToAnswerOutcome. */
  private fun processAnsweredQuestionOutcome(
    answeredQuestionOutcomeResult: AsyncResult<AnsweredQuestionOutcome>
  ): AnsweredQuestionOutcome {
    if (answeredQuestionOutcomeResult.isFailure()) {
      oppiaLogger.e(
        "QuestionPlayerFragment",
        "Failed to retrieve answer outcome",
        answeredQuestionOutcomeResult.getErrorOrNull()!!
      )
    }
    return answeredQuestionOutcomeResult.getOrDefault(AnsweredQuestionOutcome.getDefaultInstance())
  }

  private fun moveToNextState() {
    questionViewModel.setCanSubmitAnswer(canSubmitAnswer = false)
    questionAssessmentProgressController.moveToNextQuestion()
  }

  private fun hideKeyboard() {
    val inputManager: InputMethodManager =
      activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputManager.hideSoftInputFromWindow(
      fragment.view!!.windowToken,
      InputMethodManager.SHOW_FORCED
    )
  }

  private fun createRecyclerViewAssembler(
    builder: StatePlayerRecyclerViewAssembler.Builder,
    congratulationsTextView: TextView,
    congratulationsTextConfettiView: KonfettiView
  ): StatePlayerRecyclerViewAssembler {
    // TODO(#501): Add support early exit detection & message, which requires changes in the training progress
    //  controller & possibly the ephemeral question data model.
    // TODO(#502): Add support for surfacing skills that need to be reviewed by the learner.
    return builder
      .hasConversationView(hasConversationView)
      .addContentSupport()
      .addFeedbackSupport()
      .addInteractionSupport(questionViewModel.getCanSubmitAnswer())
      .addPastAnswersSupport()
      .addWrongAnswerCollapsingSupport()
      .addForwardNavigationSupport()
      .addReplayButtonSupport()
      .addReturnToTopicSupport()
      .addHintsAndSolutionsSupport()
      .addCelebrationForCorrectAnswers(
        congratulationsTextView,
        congratulationsTextConfettiView,
        MINI_CONFETTI_BURST
      )
      .addConceptCardSupport()
      .build()
  }

  private fun getQuestionPlayerViewModel(): QuestionPlayerViewModel {
    return viewModelProvider.getForFragment(fragment, QuestionPlayerViewModel::class.java)
  }

  private fun logQuestionPlayerEvent(questionId: String, skillIds: List<String>) {
    oppiaLogger.logTransitionEvent(
      oppiaClock.getCurrentTimeMs(),
      EventLog.EventAction.OPEN_QUESTION_PLAYER,
      oppiaLogger.createQuestionContext(
        questionId,
        skillIds
      )
    )
  }

  private fun showHintsAndSolutions(helpIndex: HelpIndex, isCurrentStatePendingState: Boolean) {
    if (!isCurrentStatePendingState) {
      // If current question state is not the pending top question state, hide the hint bulb.
      questionViewModel.setHintOpenedAndUnRevealedVisibility(false)
      questionViewModel.setHintBulbVisibility(false)
    } else {
      when (helpIndex.indexTypeCase) {
        HelpIndex.IndexTypeCase.NEXT_AVAILABLE_HINT_INDEX -> {
          questionViewModel.setHintBulbVisibility(true)
          questionViewModel.setHintOpenedAndUnRevealedVisibility(true)
        }
        HelpIndex.IndexTypeCase.LATEST_REVEALED_HINT_INDEX -> {
          questionViewModel.setHintBulbVisibility(true)
          questionViewModel.setHintOpenedAndUnRevealedVisibility(false)
        }
        HelpIndex.IndexTypeCase.SHOW_SOLUTION -> {
          questionViewModel.setHintBulbVisibility(true)
          questionViewModel.setHintOpenedAndUnRevealedVisibility(true)
        }
        HelpIndex.IndexTypeCase.EVERYTHING_REVEALED -> {
          questionViewModel.setHintOpenedAndUnRevealedVisibility(false)
          questionViewModel.setHintBulbVisibility(true)
        }
        else -> {
          questionViewModel.setHintOpenedAndUnRevealedVisibility(false)
          questionViewModel.setHintBulbVisibility(false)
        }
      }
    }
  }
}
