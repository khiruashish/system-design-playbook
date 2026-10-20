package in.grocery.catalog;
class CatalogService {
  @Autowired KafkaTemplate<String, PriceChanged> kafka;
  void reprice(Sku s) { kafka.send("price-changes", s.id(), new PriceChanged(s)); }   // undocumented topic
}
