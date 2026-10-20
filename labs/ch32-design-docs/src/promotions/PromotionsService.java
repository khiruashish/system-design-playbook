package in.grocery.promotions;
@FeignClient(name = "catalog", url = "${services.catalog}")
interface CatalogClient { Sku sku(String id); }
