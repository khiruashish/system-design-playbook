package in.grocery.checkout;
@FeignClient(name = "catalog", url = "${services.catalog}")
interface CatalogClient { Price price(String sku); }
@FeignClient(name = "promotions", url = "${services.promotions}")      // not in the documented model
interface PromotionsClient { Discount best(String cartId); }
class CheckoutService {
  @Autowired KafkaTemplate<String, OrderPlaced> kafka;
  void place(Order o) { kafka.send("order-events", o.id(), new OrderPlaced(o)); }
}
