# Design / Impact Note

**Domain keywords considered:** analytics, rate limit, redirect, alias, expiration, health, metrics, click

**Impacted files (18):**

- `service\src\main\java\com\schwab\urlshortener\config\AppConfig.java`
- `service\src\main\java\com\schwab\urlshortener\config\ServiceGovernanceFilter.java`
- `service\src\main\java\com\schwab\urlshortener\metrics\ServiceMetrics.java`
- `service\src\main\java\com\schwab\urlshortener\model\ClickEvent.java`
- `service\src\main\java\com\schwab\urlshortener\model\ServiceExceptions.java`
- `service\src\main\java\com\schwab\urlshortener\model\UrlRecord.java`
- `service\src\main\java\com\schwab\urlshortener\rate\RateLimiter.java`
- `service\src\main\java\com\schwab\urlshortener\store\InMemoryUrlStore.java`
- `service\src\main\java\com\schwab\urlshortener\store\UrlStore.java`
- `service\src\main\java\com\schwab\urlshortener\validation\AliasValidator.java`
- `service\src\main\java\com\schwab\urlshortener\validation\UrlValidator.java`
- `service\src\main\java\com\schwab\urlshortener\web\ClientHash.java`
- `service\src\main\java\com\schwab\urlshortener\web\GlobalExceptionHandler.java`
- `service\src\main\java\com\schwab\urlshortener\web\HealthController.java`
- `service\src\main\java\com\schwab\urlshortener\web\MetricsController.java`
- `service\src\main\java\com\schwab\urlshortener\web\RedirectController.java`
- `service\src\main\java\com\schwab\urlshortener\web\UrlItemController.java`
- `service\src\main\java\com\schwab\urlshortener\web\UrlsController.java`
