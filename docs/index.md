# System Design Enterprise Handbook

**From your first `@RestController` to defending a national-scale architecture in a Principal Engineer review.**

This handbook teaches system design the way it is practised in enterprise Java shops. Every concept is explained from intuition to internals. It is implemented in Java 25 / Spring Boot 4, stress-tested against production failure modes, and turned into interview answers that hold up at the 15-year level.

!!! abstract "What makes this handbook different"
    - **One domain, end to end.** Every chapter uses the same multi-vendor grocery marketplace for India: OTP login, pincode serviceability, dark-store inventory, Razorpay payments, delivery slots, GST invoices. Concepts build on each other instead of resetting with every toy example.
    - **Four reader bands in every chapter.** Beginner → Intermediate → Senior → Architect, in the same chapter and on the same problem.
    - **L1 → L4 code ladder.** Each chapter solves one problem four times: plain Java, Spring, production-grade, enterprise-scale.
    - **Design documents and sequence diagrams.** These are included in every chapter, and every chapter shows a failure path alongside the happy path.
    - **20+ interview Q&As per chapter** with 30-second answers, deep answers, follow-ups and red flags.

## Structure

| Part | Focus | Band |
|---|---|---|
| **0 · Orientation** | How to read the book; what system design is | All |
| **I · Foundations** | Estimation, networking, APIs, databases, caching, load balancing, scalability | B1 |
| **II · Distributed Core** | Replication, sharding, consensus, transactions, messaging, microservices, resilience | B2 |
| **III · Production** | Data at scale, search, security, observability, performance, deployment, DR | B3 |
| **IV · Architect** | Decisions, design docs, DDD, modern styles, AI-era design, compliance, leadership | B4 |
| **V · Case Studies** | 18 classic and domain designs, from URL shortener to order matching | All |
| **VI · Capstone** | The full grocery marketplace, end to end | B3–B4 |
| **VII · Interview** | Framework, level expectations, Q&A bank, mock interviews, behavioural & leadership | All |
| **Industry Designs** | Sector designs: Amazon-style marketplace, Flipkart-style sales, banking, healthcare and more | B2–B4 |
| **Appendix** | Full question index, glossary, lab catalogue | All |

Start with [Chapter 00 — How to Use This Handbook](00-orientation/00-how-to-use-this-handbook.md).

## At a glance

- **62 chapters** (00–61), each with runnable labs whose outputs are quoted verbatim in the text.
- **1,580 interview questions** (1,220 in the chapters plus 360 in the industry designs), 495 marked ⭐ high-frequency.
- **244 labs** (four per chapter), catalogued in the [Lab Catalogue](99-appendix/lab-catalogue.md); **189 terms** in the [Glossary](99-appendix/glossary.md).

## Industry designs

- [Industry Designs tab](08-industry/index.md): end-to-end designs per sector, starting with the [Amazon-Style Global Marketplace](08-industry/i01-amazon-style-global-marketplace.md).

## Quick links for interview preparation

- [⭐ Drill Bank](07-interview/qa-bank-starred.md): every high-frequency question with its 30-second answer.
- [Full Question Index](99-appendix/qa-index.md): all questions by part, chapter and level.
- [Ch 57 — Interview Framework](07-interview/57-the-system-design-interview-framework.md) · [Ch 60 — Mock Interviews](07-interview/60-mock-interviews.md) · [Ch 61 — Behavioural & Leadership](07-interview/61-behavioral-and-leadership.md)
