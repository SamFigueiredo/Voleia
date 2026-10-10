# Placar Vôlei IA (vôlei de praia) – Android

App que usa a câmera do celular para detectar a bola, marcar pontos automaticamente, controlar sets/trocas de lado e mostrar replay dos lances principais.

## 1. Como gerar o APK

**Opção A – Android Studio (mais simples)**
1. Instale o Android Studio e abra a pasta `VoleiIA`.
2. Aguarde o *Gradle Sync* (precisa de internet; ele também baixa o modelo de IA automaticamente).
3. Menu *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)*.
4. O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`. Copie para o celular e instale
   (permita "instalar apps de fontes desconhecidas"). Ou ligue o celular por USB (depuração USB) e clique em ▶ *Run*.

**Opção B – Sem instalar nada (GitHub Actions)**
1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (inclusive `.github`).
2. Aba *Actions* ▸ "Build APK" ▸ *Run workflow*.
3. Ao terminar, baixe o artefato `placar-volei-apk` (contém o `app-debug.apk`).

**Se o modelo não baixar:** baixe o `efficientdet_lite0` (versão *detection/metadata*, `.tflite`) e salve como
`app/src/main/assets/efficientdet_lite0.tflite`. Sem ele o app abre em modo manual (botões de ponto + replay).

## 2. Como usar
1. Fixe o celular em tripé, **de lado, na altura da rede**, vendo a quadra inteira (rede no meio da imagem), na horizontal.
2. *Menu ▸ Calibrar quadra*: toque nos 4 cantos da quadra na ordem
   distante-esquerdo → distante-direito → próximo-direito → próximo-esquerdo. Salve. **Não mexa mais no celular.**
3. *Menu ▸ Configurações*: nomes dos times, nº de sets, pontos por set, set decisivo, replay e confiança mínima.
4. Jogue. Quando a IA tem certeza, marca o ponto sozinha; quando tem dúvida, pergunta
   (Ponto A / Ponto B / Ignorar). Os botões `+1` e `Desfazer` corrigem qualquer erro.
5. Troca de lados: o placar avisa a cada N/3 pontos (7 em set de 21, 5 em set de 15) e ao fim de cada set. Menu permite trocar manualmente.

## 3. Como funciona
- **Detecção:** EfficientDet-Lite0 (COCO, classe "sports ball") com TensorFlow Lite. Procurando a bola: 2 recortes do quadro; rastreando: recorte ao redor da posição prevista.
- **Calibração:** homografia dos 4 cantos converte a imagem em metros da quadra (16 × 8 m) → dentro/fora e lado.
- **Pouso:** bola descendo na faixa da areia → "assentou" (ou sumiu) → ponto. Subida forte = defesa, ignorada.
- **Confiança:** continuidade do rastreio, distância da linha, confiança do detector. "Fora" por pouco sempre pergunta.
- **Replay:** buffer circular de ~30 s em memória; após o ponto toca o rali (em câmera lenta no final). Toque para pular.

## 4. Limitações (honestas)
- Modelo genérico: bola pequena/distante pode falhar. Isso é esperado; o caminho de melhoria é treinar um YOLO com vídeos da sua quadra.
- Oclusão por jogadores, sol/areia clara e bolas paradas ao fundo geram erros; por isso existe a confirmação manual.
- Placar não é salvo se o app fechar. Replay tem resolução 640 px.
- Ajuste fino dos limiares: constantes no topo de `BallTracker.kt`.
