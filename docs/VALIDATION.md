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

## Ciclo foreground, galeria e compartilhamento local

Em 2 de novembro de 2026, o commit `5f70c5e165b19c7e` passou no job autorizado `haval-camera-5f70c5e165b19c7e-20261102T194320Z-29565`. A fila executou os testes JVM e montou os APKs debug e release; assinatura, alinhamento e integridade ZIP também passaram. Os testes cobrem reducer de ciclo, configurações seguras e sessão LAN autenticada, além dos testes anteriores.

Esta evidência é somente de software. Não foram executados o Step 8 nem os Gates de hardware: gravação standalone no carro, morte de processo, reinício do Shizuku/central, reprodução real dos segmentos, roteamento Wi-Fi entre telefone e central, retomada e SHA-256 de download, fechamento físico da porta, AVM OEM, carga ou temperatura. O APK não deve ser publicado como compatível até esses testes passarem.
