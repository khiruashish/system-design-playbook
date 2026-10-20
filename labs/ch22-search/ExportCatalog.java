// ExportCatalog.java — writes the catalog as CSV for the PostgreSQL and Meilisearch labs: /opt/jdk25/bin/java ExportCatalog.java 200000 > products.csv
public class ExportCatalog {
    public static void main(String[] a) {
        var out = new StringBuilder("id,type,name,brand,category,size,popularity,in_stock,price_paise\n");
        for (var p : Catalog.products(Integer.parseInt(a[0])))
            out.append(p.id()).append(',').append(p.type()).append(',').append(p.name()).append(',').append(p.brand()).append(',').append(p.category())
               .append(',').append(p.size()).append(',').append(p.popularity()).append(',').append(p.inStock()).append(',').append(p.pricePaise()).append('\n');
        System.out.print(out);
    }
}
