# MLR Load Predictor (多元線性回歸 - Multiple Linear Regression)

---

## 專案簡介

此專案用於分析Minecraft 伺服器中不同玩家類型對伺服器耗能的影響以及該伺服器的玩家數量趨勢(可選)  
本插件支援[Orchestrator-paper](https://github.com/kfcisme/orchestrator-paper)與[LogPlayerAction](https://github.com/kfcisme/LogPlayerAction_paper)  
其中 Database 僅支援 mySQL, 資料格式請與 [LogPlayerAction](https://github.com/kfcisme/LogPlayerAction_paper) 一致

---

## 功能

- 支援 **Spigot / Paper 插件**
- 支援 **ONNX Runtime** 載入玩家分類模型 [訓練好的模型](https://github.com/kfcisme/mlp_player_classifier)
> 若想自行訓練模型建議使用 [2026TISF_Kmeans](https://github.com/kfcisme/2026TISF_Kmeans) , 與　[mlp_player_classifier](https://github.com/kfcisme/mlp_player_classifier)  
> 若想自行訓練請務必注意資料格式
- 支援從 MySQL 讀取玩家事件資料
> 僅支援mySQL
- 可選擇串接 **LSTM 微服務** 預測下一時間窗組成
> LSTM結果因人而異，使用前請注意串接接口
- 可選擇啟用 **AutoScaler** 做資源擴縮判斷
> 或是選用更精準的調節系統 [orchestrator-paper](https://github.com/kfcisme/orchestrator-paper)

---

## 運行要求

- Java JDK 21
- Minecraft 1.20.4 以上 paper / spigot 版本
- MySQL 5.7 以上

---

## config.yml 設定說明

1) 玩家分類模型請輸出成 ONNX 格式，並將路徑填入 `onnx_model_path`  
> 路徑建議放在插件資料夾內，或是絕對路徑皆可
```yaml
server_id: "your_server_id"

onnx_model_path: "plugins/MLPLoadPredictor/mlp_player_classifier.onnx"
unknown_threshold: 0.55
```

2) 資料庫設定請填入正確的連線資訊，並確保資料庫中有符合格式的玩家事件資料，```pool_size```建議維持不變。

```yaml
mysql:
  url: "jdbc:mysql://127.0.0.1:3306/mc_analytics?useSSL=false&serverTimezone=UTC"
  username: "mc_user"
  password: "mc_pass"
  pool_size: 8

window_minutes: 30
```

3) 回歸模型設定可以自行填入訓練好的回歸模型參數，包含截距(intercept)、各玩家類型的係數(beta)以及自回歸項(ar)，以便插件能夠準確預測伺服器耗能  
若沒有訓練好的模型，可以留白或自訂  
支援 cpu, memory, tps 的回歸訓練

```yaml
regression:
  target: "cpu"
  intercept: 8.0
  beta:
    AFK:        0.2
    Build:      1.3
    Explorer:   0.9
    Explosive:  1.8
    PvP:        2.0
    Redstone:   1.7
    Social:     0.7
    Survival:   1.0
  ar:
    y_lag1: 0.45
    y_lag2: 0.20
```

4) LSTM支援  
請先確認LSTM接口運作是否正常再於此設定。

```yaml
lstm:
  enabled: false
  base_url: "http://127.0.0.1:8900"
  timeout_ms: 2000
```

5) 僅提供最基礎且手動的自動調節，若需要進階內容請參考[orchestrator-paper](https://github.com/kfcisme/orchestrator-paper)

```yaml
autoscale:
  enabled: false
  cpu_thresholds: [60, 130, 190, 250]
  cool_down_minutes: 10
```

---

## 資料庫相關進階說明

本插件會使用與寫入以下資料表：

- player_events_30m：玩家事件原始資料
- player_type_pred：玩家類型推論結果
- server_comp_30m：伺服器玩家組成比例
- server_load_pred_30m：伺服器負載預測結果
- server_load_30m：歷史伺服器負載資料（供 lag 項使用）

若使用[LogPlayerAction](https://github.com/kfcisme/LogPlayerAction_paper)相同資料庫可延續使用。