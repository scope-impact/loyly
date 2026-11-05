// Proves the vendored library set compiles offline; not shipped to candidates.
use axum::{routing::get, Json, Router};
use serde::Serialize;

#[derive(Serialize)]
struct Health {
    ok: bool,
}

#[tokio::main]
async fn main() {
    let app: Router = Router::new().route("/health", get(|| async { Json(Health { ok: true }) }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let _ = (app, listener, serde_json::json!({}));
}

#[cfg(test)]
mod tests {
    #[test]
    fn client_builds() {
        reqwest::Client::new();
    }
}
