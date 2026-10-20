package in.grocery.dispatch;
class DispatchListener {
  @KafkaListener(topics = "order-events") void on(OrderPlaced e) { notify(e); }
  private final WebClient sms = WebClient.create("https://sms.gateway.example/v2");
  // NOTE: dispatch used to call checkout directly; that call was removed in 2026-06
}
