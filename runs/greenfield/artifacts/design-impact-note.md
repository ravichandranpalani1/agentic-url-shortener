# Design / Impact Note

**Domain keywords considered:** analytics, rate limit, redirect, alias, expiration, health, metrics, click

**Impacted files (18):**

- `service/src/main/java/com/schwab/urlshortener/Bootstrap.java`
- `service/src/main/java/com/schwab/urlshortener/http/ExceptionMapper.java`
- `service/src/main/java/com/schwab/urlshortener/http/HealthHandler.java`
- `service/src/main/java/com/schwab/urlshortener/http/HttpUtil.java`
- `service/src/main/java/com/schwab/urlshortener/http/MetricsHandler.java`
- `service/src/main/java/com/schwab/urlshortener/http/RateLimitFilter.java`
- `service/src/main/java/com/schwab/urlshortener/http/RedirectHandler.java`
- `service/src/main/java/com/schwab/urlshortener/http/UrlItemHandler.java`
- `service/src/main/java/com/schwab/urlshortener/http/UrlsCollectionHandler.java`
- `service/src/main/java/com/schwab/urlshortener/metrics/ServiceMetrics.java`
- `service/src/main/java/com/schwab/urlshortener/model/ClickEvent.java`
- `service/src/main/java/com/schwab/urlshortener/model/ServiceExceptions.java`
- `service/src/main/java/com/schwab/urlshortener/model/UrlRecord.java`
- `service/src/main/java/com/schwab/urlshortener/rate/RateLimiter.java`
- `service/src/main/java/com/schwab/urlshortener/store/InMemoryUrlStore.java`
- `service/src/main/java/com/schwab/urlshortener/store/UrlStore.java`
- `service/src/main/java/com/schwab/urlshortener/validation/AliasValidator.java`
- `service/src/main/java/com/schwab/urlshortener/validation/UrlValidator.java`
