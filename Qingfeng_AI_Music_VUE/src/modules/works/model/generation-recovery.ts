export function needsGenerationRefresh(tasks: readonly {status:string}[], awaitingResponse=false) {
  return awaitingResponse || tasks.some(task=>['QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'].includes(task.status))
}
