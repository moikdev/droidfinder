package dev.findroid

/**
 * Explicit reset for a test's owned fixture boundary. The acceptance harness preserves the
 * original private state; reset itself preserves the old journal and never deletes documents.
 * Do not use between steps that are intended to exercise history or process continuity.
 */
internal fun resetOwnedUndoFixture(model: FinderModel) {
    check(model.busy == null) { "The previous fixture operation must finish before its Undo reset." }
    model.session.history.resetSavedHistory()
    model.session.undoLabel.value = null
    model.session.redoLabel.value = null
    model.session.recentActions.value = emptyList()
    model.session.historyFailure.value = null
    model.session.historyRecoveryProblem.value = null
    model.session.historyPersistenceNotice.value = null
}
