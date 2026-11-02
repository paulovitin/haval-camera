# Registro de validação

## Sessão de 2 de novembro de 2026

Testamos uma central de Haval H6 identificada pelo Android como `msmnile_gvmq_for_arm64`, product/device `gwm`, com o carro parado em P. Não registramos uma identificação de firmware suficiente para anunciar compatibilidade com todas as centrais desse modelo.

| Verificação | Resultado |
| --- | --- |
| Enumerar origem Qualcomm | Entrada ID 4, 1760 × 720, formato anunciado `0x07080300`, 30 fps. |
| Capturar imagem real | Quadro BGR24 com visão 360° e traseira, correspondente à câmera física. |
| Captura curta com interface AVM fechada | Recebemos um quadro real; isso não valida todos os estados futuros da central. |
| Mostrar dentro do APK | Imagem com cores corretas em `CameraPreview`; usuário confirmou os controles do app na tela física. |
| Atualização contínua observada | Logs avançaram de 1 para 150, 300 e 450 quadros, cerca de 15 fps ao longo de 30 segundos. Não representa teste prolongado. |
| Gravação de frames no armazenamento | `/data/vendor/camera` permaneceu vazio durante a verificação do APK. |
| Fechar/pausar e conferir processos | Implementado; verificação final ficou pendente quando ADB/Telnet perderam conexão. |
| Reabrir e reconectar | Pendente de validação completa. |
| Reiniciar a central | Não testado. |
| Outro app usando a câmera simultaneamente | Não testado. |
| Outras centrais/firmwares ou veículo em movimento | Não testado. |

A primeira imagem real preservada nesta investigação tem horário 14h13. O projeto do APK começou às 14h17 e o primeiro quadro do app funcional apareceu às 14h37. Esses horários vêm dos arquivos e logs locais, não de uma medição completa do tempo de conversa.

## Publicação do exemplo

O código Java, AIDL, C e o manifesto desta versão vêm do protótipo 0.2 que mostrou vídeo no carro. A preparação do repositório acrescenta documentação, build para macOS/Linux, verificação de dependências, CMake para integração e avisos de licença no APK. O workflow verifica compilação e empacotamento; ele não substitui a validação física acima.

O build local no macOS passou com JDK 17 e os componentes de SDK/NDK fixados no script. Também passaram a compilação pelo CMake, `apksigner verify`, `zipalign`, a integridade do ZIP e `actionlint`. A comparação com o APK testado confirmou bytes idênticos nos seguintes arquivos executáveis e no manifesto:

| Arquivo dentro do APK | SHA-256 |
| --- | --- |
| `classes.dex` | `d409752efe1549d4f7da0341aca5b8813107dacef2bb556047bae6abd1ecf9cc` |
| `lib/arm64-v8a/libcamera_stream.so` | `be064b23418d3e9cc3f88eb4fe95e43beade9acecf4387ab7c8f3dab5c26f87d` |
| `AndroidManifest.xml` | `9874585469131f8e654d39520916eb333e8412eb8eab824234b3c286613f5b36` |

Ao gerar um release, registre o SHA-256 do APK nos assets. Builds podem produzir assinaturas diferentes conforme o keystore usado. Compare também `classes.dex`, a biblioteca nativa e o manifesto quando afirmar que um novo empacotamento preserva o código testado.

As fotografias da garagem, quadros raw, logs completos e cópias de binários OEM usados na investigação não fazem parte do repositório público.

## Evidência de software da gravação segmentada

Em 2 de novembro de 2026, o commit `012a9576c9b0d79b` foi verificado no Mac Mini autorizado pelo job `haval-camera-012a9576c9b0d79b-20261102T192959Z-11247`. O job executou `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`; os testes unitários, incluindo os contratos de coordenação da gravação e do overlay de telemetria, passaram e o APK debug foi montado. O mesmo job também concluiu `build.sh`, incluindo o APK release assinado e as verificações de assinatura, alinhamento e integridade ZIP. O APK release obtido tem SHA-256 `d5005db7c051f6946ee88f70f18e502431fde00a4cc5e567d2065c3ec9ed902b`.

A compilação emitiu os sete avisos já conhecidos de anotações AndroidX e API obsoleta em `CameraActivity`; não houve erro de compilação, teste ou empacotamento. Esse resultado comprova somente o escopo de software. O Gate 1 continua pendente: nenhum APK desta implementação foi instalado no carro e ainda não há evidência de encoder AVC no hardware, gravação por 10 minutos, reprodução e hashes de seis segmentos, igualdade do overlay, ausência de dumps raw, ciclo fechar/reabrir, processos órfãos, CPU, memória ou temperatura.

## Acrescentar evidência

Ao testar outra central, registre modelo, versão Android, identificação pública do firmware, versão do Shizuku e modo de execução, commit/versão do APK, duração observada e resultado de cada verificação. Remova VIN, endereços de rede, credenciais e imagens pessoais antes de compartilhar diagnósticos. Marque como pendente qualquer cenário que não tenha sido executado.

## Produtor de vídeo ao vivo

Em 2 de novembro de 2026, o commit `b9f9bd2a6c079111` passou no job autorizado `haval-camera-b9f9bd2a6c079111-20261102T212712Z-3090`. A fila executou 110 testes JVM e montou os APKs debug e release. Assinatura, alinhamento, lint vital e integridade ZIP passaram. O APK release tem SHA-256 `afe11b88d706814fd9f600286ed7f46546d483049139d7bc052b29ca816b7f9c`.

Os testes cobrem fila limitada a 1 MiB ou 30 mensagens, recuperação por keyframe, CONFIG antes do primeiro access unit, autenticação no header `Authorization: Bearer`, ausência do token na URL, WSS obrigatório, expiração sem reutilização, pedido de keyframe no formato do relay, códigos de fechamento e isolamento entre falha de rede e gravação local. OkHttp permanece em 5.4.0; nenhuma dependência de teste foi adicionada.

Esta evidência é somente de software. Não houve instalação, publicação ou teste de rede no carro. WSS real, MQTT/IPC instalado, frames do encoder físico, perda e retorno de 4G, latência, gravação simultânea, grace period de 15 segundos, AVM OEM, carga e temperatura permanecem pendentes nos gates de hardware.

Na revisão corretiva da Task 8, o commit `619b94fca8f03bff6a72fce0ae66eedee4fc3be3` passou no job autorizado `haval-camera-619b94fca8f03bff-20261102T213410Z-15803`. Os 120 testes JVM e os mesmos gates de debug, release, lint vital, assinatura, alinhamento e integridade ZIP passaram. O APK release tem SHA-256 `f5c78c247403591f3b0460bdb327975f8fdc1967cade2e88bd38ba5bc8c2df98`; o log tem SHA-256 `a0a04ab41f10dff6d9d9def6e9f1bffb6d4fab4e8ef1ead02f63cadc430961f0`; o snapshot imutável enviado tem SHA-256 `c7f69cdb90f58d1377e9e81a9a56afa623d013d1de600e919b26650cd9db91a4`.

As regressões adicionais cobrem rejeição de live desabilitado antes do journal, confirmação lógica do viewer, isolamento de STOP obsoleto por ID de sessão, orçamento combinado de 1 MiB entre fila local e WebSocket, recuperação após rejeição do executor, CONFIG sempre seguido de keyframe e rejeição de AVCC/NAL inválido. Nenhuma dependência mudou. Os limites de hardware e integração descritos acima continuam iguais.

Na segunda rodada corretiva, o commit `5e64cacb9c2c3e81cd9693cacd1cd2885c1d444b` passou no job autorizado `haval-camera-5e64cacb9c2c3e81-20261102T213920Z-7429`. Os 124 testes JVM e todos os gates de pacote passaram. O APK release tem SHA-256 `d86321ef8c55cdf6af3c8a4f64a2f49a0ea0040fcedd270831d15e8ada811f2d`; o log tem SHA-256 `92d92e8ffca5a4437ec068666b1eb012f9393a1e5945f817b82b63378bda3324`; o snapshot imutável tem SHA-256 `aad50f6822eb2ff405836e92854225ffa07f7766c3d9b2fcfa1e3448e6b1b295`.

Esta rodada tornou atômicos, sob o lock do cliente, o snapshot de `WebSocket.queueSize()` e o enqueue local. O produtor não envia outra mensagem enquanto o transporte reporta bytes pendentes; esse transporte ocupa conservadoramente um dos 30 slots. O limite combinado permanece em no máximo 1 MiB, e transporte já no limite ou acima força recuperação e pedido de keyframe. Os limites de hardware e integração continuam inalterados.

## Ciclo foreground, galeria e compartilhamento local

Em 2 de novembro de 2026, o commit `5f70c5e165b19c7e` passou no job autorizado `haval-camera-5f70c5e165b19c7e-20261102T194320Z-29565`. A fila executou os testes JVM e montou os APKs debug e release; assinatura, alinhamento e integridade ZIP também passaram. Os testes cobrem reducer de ciclo, configurações seguras e sessão LAN autenticada, além dos testes anteriores.

Esta evidência é somente de software. Não foram executados o Step 8 nem os Gates de hardware: gravação standalone no carro, morte de processo, reinício do Shizuku/central, reprodução real dos segmentos, roteamento Wi-Fi entre telefone e central, retomada e SHA-256 de download, fechamento físico da porta, AVM OEM, carga ou temperatura. O APK não deve ser publicado como compatível até esses testes passarem.
