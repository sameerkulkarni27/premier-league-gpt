/**
 * Renders the "text" answer_type from PLAN.md: data is null, summary is the
 * whole answer.
 */
export default function TextBubble({ summary }) {
  return <p className="text-bubble">{summary}</p>
}
