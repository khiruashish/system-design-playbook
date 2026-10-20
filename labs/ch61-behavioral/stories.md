# Story bank (illustrative — replace with your own)

## S1 Checkout outage in the festival sale
- competencies: incident, pressure, ownership, communication
- level: B3
- situation: During the festival sale our checkout error rate went from 0.2% to 18% within four minutes of the 8 pm deal drop. I was the on-call lead for the checkout domain that night.
- task: I had to restore checkout quickly, keep leadership and customer support informed, and make sure it could not happen the same way again.
- action: I declared a SEV1, split the bridge into a mitigation lead and a communications lead, and asked for the last three changes. I found that a pricing cache TTL change had moved load onto the promotions database. I rolled back the config, turned on the static-price fallback for the deal SKUs, and posted updates every ten minutes. Afterwards I ran the blameless postmortem and owned three of the actions: a load test for sale-day config, a canary rule for TTL changes, and a runbook for the fallback.
- result: Checkout recovered in 23 minutes. We estimated about 41,000 affected sessions. The next two sales ran without a checkout SEV, and TTL changes now go through a canary.
- learning: I now ask for "what changed" in the first five minutes of every incident, and I push for config changes to be treated like code.

## S2 Disagreeing with the principal architect on event sourcing
- competencies: conflict, technical-decision, influence
- level: B3
- situation: The principal architect proposed event sourcing for the whole order domain. I believed it would slow the team for a year with little benefit.
- task: I needed to change the decision without damaging the relationship, and stay open to being wrong.
- action: I wrote a two-page comparison with three options and costs, including a prototype of the order history query both ways. I met him one-to-one before the review so he wasn't surprised, and I proposed event sourcing only for the payment ledger, where the audit trail mattered, with an outbox for the rest. I agreed a metric that would make us revisit it.
- result: The review adopted the hybrid. The ledger shipped in six weeks instead of the estimated five months for the full approach, and two years later we still had not needed full event sourcing for orders.
- learning: Disagreement lands better with a prototype, a pre-meeting and a condition for changing my mind.

## S3 My failed search migration
- competencies: failure, ownership, learning
- level: B2
- situation: We were moving product search from a self-hosted cluster to a new version and I planned the cutover.
- task: Cut over with no visible impact.
- action: We did the cutover on a Tuesday and we saw that relevance was worse for Hindi queries. We rolled back after two hours. We had tested only English queries in the shadow comparison.
- result: It was rolled back.
- learning: Test more languages.

## S4 Mentoring a new engineer into on-call
- competencies: mentoring, growing-others, communication
- level: B2
- situation: A new engineer, Priya, joined the dispatch team and was anxious about joining on-call after four weeks.
- task: Get her confident and effective on-call without throwing her in.
- action: I paired with her on three shadow shifts, then reverse-shadowed, where she led and I watched. I gave her ownership of rewriting two runbooks she had found confusing, and we did a weekly 30-minute review of real alerts.
- result: She joined the rotation in week seven. Her runbook rewrites cut the median time to mitigate for those two alerts from 22 to 9 minutes, and she later mentored the next new joiner the same way.
- learning: Ownership of something small builds confidence faster than more shadowing.

## S5 Choosing not to build a feature store
- competencies: technical-decision, strategy, cost, ambiguity
- level: B4
- situation: Three teams asked for a feature store for recommendations, fraud and ETA models. The vendor quotes were large and the requirements were vague.
- task: I was asked to recommend a platform direction for the next 18 months.
- action: I interviewed the three teams and measured how features were actually used: 80% were batch features read once a day, and only ETA needed sub-second online features. I proposed a shared offline feature table with conventions, plus a small online store only for ETA, and wrote an ADR with a trigger to revisit if two more teams needed online features.
- result: We saved roughly the vendor cost for the year, the ETA team shipped in one quarter, and the revisit trigger fired 14 months later, at which point we bought a managed store with clear requirements.
- learning: A clear "not yet" with a trigger is often better than a big platform bet on vague requirements.

## S6 Aligning four teams on an API deprecation
- competencies: influence, cross-team, delivery, communication
- level: B3
- situation: We had to retire the v1 cart API, which four teams still called, before a payment-provider change deadline.
- task: I had no authority over the other teams but owned the deadline risk.
- action: I published the call volumes per team, offered a migration library and office hours, set dated milestones with each team lead, and escalated one team's blocker early to their director with a proposed fix rather than a complaint.
- result: All four teams migrated nine days before the deadline and v1 traffic went to zero without an incident.
- learning: Making the cost visible and the path easy did more than asking.

## S7 Hiring bar for the platform team
- competencies: hiring, growing-others, strategy
- level: B4
- situation: Our platform team's hiring had high variance: two recent hires struggled and interviewers disagreed often.
- task: As the senior engineer on the hiring panel, improve our hiring decisions without slowing hiring.
- action: I introduced anchored rubrics per interview, had interviewers write feedback before the debrief, ran four calibration sessions on recorded interviews, and tracked each interviewer's pass rate monthly.
- result: Verdict agreement in shadow scoring rose from about two-thirds to over eighty percent, time to hire stayed the same, and the next six hires all passed their six-month review.
- learning: Structure beats instinct in hiring, and interviewers welcome it when it saves them debate time.

## S8 Customer-facing delay in slot booking
- competencies: customer-focus, ambiguity, technical-decision
- level: B2
- situation: Customers complained that the slot they booked showed as full when they returned to change the order.
- task: I was asked to fix the complaints in the slot booking service.
- action: We found the capacity check counted the customer's own booking. We changed the query to exclude the customer's own reservation and added a test.
- result: Complaints went down.
- learning: Look at the customer journey, not just the API.
