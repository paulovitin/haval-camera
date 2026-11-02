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

## Correção de startup do catálogo no Android 9

O usuário forneceu uma reprodução no carro, Android 9, pacote 0.2, capturada em 2 de novembro de 2026 às 19h19: `ExceptionInInitializerError` em `VideoCatalog.<clinit>` causado por `PatternSyntaxException` no final de `TRIP_JSON`. O crash ocorre em `VideoCatalogProvider.onCreate`, antes da Activity. Essa evidência posterior invalida qualquer inferência de compatibilidade de startup baseada somente nos testes JVM anteriores.

O commit `3a07077da19f71a7a2d8832a86dcc89b42814fed` escapa somente os delimitadores JSON literais finais de `TRIP_JSON` e `SEGMENT_JSON`; formato, grupos de captura e lógica do catálogo permanecem iguais. O commit de testes `a8a29632d71729a09f9aa1a3ff0a2f23492b6245` falhou como esperado no job `haval-camera-a8a29632d71729a0-20261102T222217Z-9862`: 126 testes, exatamente duas falhas nas novas verificações dos delimitadores. A regressão existente de recuperação também passou a verificar metadados e igualdade byte a byte do índice JSON regenerado.

O job autorizado `haval-camera-3a07077da19f71a7-20261102T222305Z-16002` passou nos 126 testes JVM de 20 suítes, incluindo os 15 testes do catálogo, sem falhas, erros ou testes ignorados. Debug, release, lint vital, assinatura com certificado EcoTrip aprovado, alinhamento e integridade ZIP passaram no Mac Mini. SHA-256 do APK: `8212888f14a691c73f921a19f31fe1db122431b92731ab766bcb3384787bfa65`. Permanecem os avisos de anotações AndroidX, API obsoleta e `extractNativeLibs` no manifesto.

Nenhum build local, instalação, publicação, tag ou acesso ao veículo ocorreu nesta correção. O startup após a correção ainda precisa de confirmação física no Android 9; os demais gates de hardware continuam pendentes. Revisão realizada pelo próprio implementador, sem subagentes.

## Gate de gravação manual após a correção Android 9

O usuário confirmou independentemente o startup corrigido no Android 9: Activity estável, sem crash. Na tentativa autorizada de gravação manual por 10 segundos, o cliente OEM anunciou o primeiro frame, depois repetiu `ERR dumping qcarcam frame /data/vendor/camera/frame_0_2.raw` e `abort_test`; `CameraCaptureClient` recebeu `EOFException`. A notificação mostrou `Erro: Muxer is closed`; foram relatados três `trip.json` incompletos e nenhum MP4 ou `current.partial`. Essas são observações fornecidas pelo usuário, sem novo acesso ao veículo nesta investigação.

A inspeção confirma que `camera_stream.c` rejeita modos diferentes de `w+` com `EINVAL`, mas o erro OEM fornecido não identifica o modo usado, o errno nem se a falha ocorreu em `fopen` ou na escrita. O adaptador nativo não mudou desde o baseline de captura anterior. Não foi encontrado um binário OEM local que permita verificar estaticamente essa chamada. A incompatibilidade de modo continua hipótese; falha de escrita no pipe ainda não foi descartada. A mensagem do muxer, por si só, não determina a origem da interrupção.

Nenhuma correção especulativa de modo, caminho, pipe ou muxer foi aplicada. A gravação continua bloqueada para diagnóstico: é necessário obter, em sessão de hardware separadamente autorizada, o modo e o errno da abertura/escrita que falha, ou fornecer um artefato OEM offline que permita inspecionar a chamada. Nenhum teste/build novo foi executado e nenhum APK novo foi gerado nesta investigação. Os resultados de pacote da correção de startup acima continuam válidos apenas para aquele commit.

### Reserva de armazenamento e erro principal

Evidência posterior da mesma execução confirmou cerca de 2,4 GiB livres com a reserva configurada em 5 GiB. O gate de `SegmentMuxer` recusou a gravação antes de criar `current.partial` com `Reserva de armazenamento indisponível`; a falha posterior `Muxer is closed` substituiu essa causa na notificação. Isso identifica a reserva, e não o modo de dump OEM, como a causa da ausência do arquivo parcial nessa tentativa específica.

A correção mantém 5, 10 e 20 GiB para instalações que já escolheram esses valores, aceita também 1 GiB e usa 1 GiB somente quando `reserveGb` ainda não existe. O reducer mantém a primeira falha do pipeline até um novo início normal limpar o erro. A verificação de software não substitui uma nova gravação física no Android 9; criação, finalização e reprodução de segmentos continuam pendentes no veículo.

## Ciclo foreground, galeria e compartilhamento local

### Task 1: seleção do Wi-Fi ativo

O commit de testes `5f4f93ac40dced1a31530baeb528aaf9fa750504` foi verificado primeiro no job `haval-camera-5f4f93ac40dced1a-20261102T224352Z-14036`: 136 testes, exatamente nove falhas novas, nenhum erro ou teste ignorado. Oito falhas demonstraram a ausência do seletor; a nona reproduziu a rejeição indevida pelo prefixo do nome da interface. Os 126 testes anteriores e a nova rejeição de bindings inválidos passaram.

O commit `79fe2b5e47a9787b9e879a9e2042c07942737390` passou no job `haval-camera-79fe2b5e47a9787b-20261102T224536Z-23926`: 136 testes JVM em 21 suítes, zero falhas, erros ou testes ignorados. Os oito testes de seleção cobrem IPv4 privado, ordem dos candidatos e rejeição de transporte não Wi-Fi, loopback, IPv6, endereços públicos e lista vazia. O teste com socket real confirmou o atendimento no endereço privado selecionado sem exigir prefixo de interface, a recusa de conexão pelo loopback na mesma porta e o fechamento da porta após encerrar o servidor.

A fila montou debug com Java/AIDL e native ARM64, e release com lint vital, assinatura fixada, alinhamento e integridade ZIP. SHA-256 do APK release: `e8718da41b3a5248af5e0f4544daa261b87d9000430193d7de7d510d28f37110`. As permissões normais `ACCESS_NETWORK_STATE` e `INTERNET` foram adicionadas para a consulta Android e o socket. Nenhuma dependência ou controle de build foi alterado.

Esta evidência não valida a execução de `ConnectivityManager` ou a apresentação da Activity no Android 9 físico. Wi-Fi Starlink Mini, central e telefone na mesma rede, isolamento de clientes, troca/perda de rede e download real continuam pendentes. A seleção é consultada ao iniciar/reiniciar o compartilhamento; não foi adicionado observador de mudanças de rede nem rebinding automático. Nenhum build/SDK local, teste de UI, instalação ou publicação foi executado; revisão feita pelo próprio implementador, sem subagentes. Os avisos já conhecidos de anotações AndroidX, API obsoleta e `extractNativeLibs` permanecem. O defeito separado `Muxer is closed` não foi alterado.

### Evidência anterior do ciclo local

Em 2 de novembro de 2026, o commit `5f70c5e165b19c7e` passou no job autorizado `haval-camera-5f70c5e165b19c7e-20261102T194320Z-29565`. A fila executou os testes JVM e montou os APKs debug e release; assinatura, alinhamento e integridade ZIP também passaram. Os testes cobrem reducer de ciclo, configurações seguras e sessão LAN autenticada, além dos testes anteriores.

Esta evidência é somente de software. Não foram executados o Step 8 nem os Gates de hardware: gravação standalone no carro, morte de processo, reinício do Shizuku/central, reprodução real dos segmentos, roteamento Wi-Fi entre telefone e central, retomada e SHA-256 de download, fechamento físico da porta, AVM OEM, carga ou temperatura. O APK não deve ser publicado como compatível até esses testes passarem.

## Task 3: atualização GitHub confirmada

O commit `8753405da5a6bcb4a7c69d06b987e6684020246e` passou no job autorizado `haval-camera-8753405da5a6bcb4-20261102T233341Z-27239`: 171 testes em 28 suítes, zero falhas/erros/ignorados, debug, release assinado, lint vital, certificado aprovado, alinhamento, ZIP e geração do manifesto a partir do APK real. APK SHA-256: `d27fb192db1c6c868eaefdba7bc95fd1543651843d59aed2e38e807959d780bf`; manifesto SHA-256: `0421e8c225d92db6ddc9b720d94b74ad4dd78a3e76ee5136e64392bab5067546`. O manifesto contém versionCode 2, versionName 0.2 e 3.530.029 bytes; tamanho/hash coincidem com o APK retornado. Não houve publicação nem incremento de versão.

O RED completo `b14eca1` falhou em exatamente 27 novas asserções entre 170 testes; os 142 testes anteriores passaram. A regressão Android 9 `71f5617` falhou somente na coleta de certificados, entre 171 testes. A correção solicita GET_SIGNATURES junto com GET_SIGNING_CERTIFICATES, pois o PackageManager do Android 9 inicial exige o primeiro para validar certificados do arquivo, e compara somente os signatários atuais de SigningInfo. O relatório detalha também os ensaios que falharam por compilação, sem tratá-los como prova RED.

Parser, seleção, hashes, exclusão, limites, consentimento, cache e HTTP injetado têm testes JVM; o script de publicação tem fixtures e execução real na fila. PackageManager, diálogos, FileProvider próprio sem AndroidX, grants de URI, permissão de fontes desconhecidas e instalador continuam pendentes no Android 9 físico. Captura/gravação ativa bloqueia download/handoff, sem forçar parada. Não houve alteração de código de captura/nativo, Wi-Fi ativo ou wrapper Voltar, dependência nova, build no coordenador, alteração do controller ledger ou revisão por subagente. Os avisos já conhecidos de anotação, depreciação e extractNativeLibs permanecem. Evidência completa: `.superpowers/sdd/2026-11-02-local-controls-and-updates/task-3-report.md`.

### Task 3, correção 1: encerrar visualização e aguardar gravação

O RED `bbe645d` falhou em oito novas regressões entre 180 testes; os 171 testes anteriores passaram. O GREEN `e76c929bc27f060176db9165c82a14b32539c82b`, job `haval-camera-e76c929bc27f0601-20261102T234743Z-18918`, passou em 180 testes/30 suítes, sem falhas, erros ou ignorados, além de debug, release assinado, lint vital, assinatura fixada, alinhamento, ZIP e manifesto. APK SHA-256: `a7052fc7148c886653da76cf39765d6b33e32deaa38f7b310c47c0d037e26003`.

Sete testes executam o controlador de espera com reducer/fanout reais: ocupado → oferta → encerrar apenas visualização → manter gravação/encoder → aguardar término externo → handoff único. Incluem gravação automática, manual e estacionada, cancelamento, desconexão, erro e retorno ao estado ocupado. Dois testes verificam os caminhos compilados da Activity/binder; não são execução de UI Android. A consulta funciona durante gravação, e os gates de download/instalação agora oferecem Encerrar visualização e observam ociosidade a cada 500 ms com Cancelar espera. O novo evento do reducer preserva gravações e o prazo normal da gravação estacionada; não usa a perda de viewer que encerraria essa gravação após 15 s. Os testes físicos Android 9 permanecem pendentes. Evidência exata consta na seção Fix round 1/5 do relatório Task 3.
