#!/bin/bash
# Modular monolith with the Java Platform Module System: boundaries enforced by the COMPILER.
cd "$(dirname "$0")"
compile() { rm -rf out; javac -d out --module-source-path src $(find src -name '*.java') 2>&1 | grep -v JAVA_TOOL | grep -E 'error:|^[0-9]+ error' ; }
echo "== 1. build and run the legal module graph"
compile; java -Dstdout.encoding=UTF-8 --module-path out -m grocery.app/com.grocery.app.Main 2>&1 | grep -v JAVA_TOOL | sed 's/^/   /'
echo "== 2. order-service code reaches into catalog's internals (the classic shortcut)"
cp src/grocery.order/com/grocery/order/api/OrderModule.java /tmp/OrderModule.bak
sed -i 's|import com.grocery.catalog.api.CatalogApi;|import com.grocery.catalog.api.CatalogApi;\nimport com.grocery.catalog.internal.ProductRepository;|' src/grocery.order/com/grocery/order/api/OrderModule.java
compile | sed 's|^|   |'
cp /tmp/OrderModule.bak src/grocery.order/com/grocery/order/api/OrderModule.java
echo "== 3. order calls inventory directly without declaring the dependency"
sed -i 's|import com.grocery.catalog.api.CatalogApi;|import com.grocery.catalog.api.CatalogApi;\nimport com.grocery.inventory.api.InventoryModule;|' src/grocery.order/com/grocery/order/api/OrderModule.java
compile | sed 's|^|   |'
cp /tmp/OrderModule.bak src/grocery.order/com/grocery/order/api/OrderModule.java
echo "== 4. someone makes catalog depend on order too (a cycle)"
cp src/grocery.catalog/module-info.java /tmp/cat-mi.bak
sed -i 's|exports com.grocery.catalog.api;.*|exports com.grocery.catalog.api;\n    requires grocery.order;|' src/grocery.catalog/module-info.java
compile | sed 's|^|   |'
cp /tmp/cat-mi.bak src/grocery.catalog/module-info.java
echo "== 5. restored: builds again"; compile; echo "   $(ls out | tr '\n' ' ')"
