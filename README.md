# Civ

This monorepo will eventually contain all civ projects and development

## Key Technologies

- [Gradle](https://gradle.org/) Is used as an entrypoint to build all projects in this repository.
- [Docker](https://www.docker.com/) Is used to containerize any deployed services,
  and provide a somewhat consistent development environment.
- [Ansible](https://www.ansible.com/) Is used to configure the target machines and for final deployment

## Developing Locally

### Plugins

### Containers
A docker compose stack is provided to help test containers built from
this repo. To start the stack, run the following commands:

1. Linux/MacOS: `./gradlew :ansible:build`  
   Windows: `.\gradlew.bat :ansible:build`
2. `docker compose up`

Please note that this stack is NOT suitable for production use.

Container data (world, logs, etc.) are mounted at [./containers/data](./containers/data).

Optional services may be started by enabling the profile flag, e.g. `--profile <name>`

Current services and exposed ports are:

| Name      | Ports | Description                 | Profile    |
|-----------|-------|-----------------------------|------------|
| proxy     | 25565 | TCP, Minecraft              |            |
| paper     |       | Overworld shard `main`      |            |
| northeast |       | Overworld shard `northeast` |            |
| southeast |       | Overworld shard `southeast` |            |
| zorweth   |       |                             |            |
| pvp       |       |                             |            |
| mariadb   | 3306  | TCP, Database               |            |
| postgres  | 5432  | TCP, Database               |            |
| rabbitmq  | 5672  | TCP, AMQP                   |            |
|           | 15672 | HTTP, Management UI         |            |
| grafana   | 3000  | HTTP, Grafana UI            | monitoring |

### Shards

The overworld is split across three servers that share one map: `paper` (registered with the proxy
as `main`), `northeast` and `southeast`. They meet at a single point at **0,0** - west of `x = 0` is
`main` from end to end, and east of it is split north/south at `z = 0`, so a player can stand on the
triple point and cross either way from it. The outlines live in
[proxy-config/plugins/shards/config.yml](./ansible/files/proxy-config/plugins/shards/config.yml),
which is the only place that knows them; the shards are told what they own at startup.

All three run the same image, the same `paper-config` tree and the same `paper-plugins`, and differ
only by `CIV_SHARD_NAME` and their data directory. That is on purpose: the ground either side of a
border has to be the same ground.

**Their worlds must be copies of one world, not three fresh ones.** Before starting `northeast` or
`southeast` for the first time, with the stack down:

```bash
cp -r containers/data/paper containers/data/northeast
cp -r containers/data/paper containers/data/southeast
rm -rf containers/data/{northeast,southeast}/world*/{playerdata,stats,advancements}
rm -f  containers/data/{northeast,southeast}/plugins/Shards/mirror.json.gz
```

Player data is the proxy's now - a shard that finds a stale local copy of a player refuses them
rather than loading it - and `mirror.json.gz` is one shard's view of its neighbours, so neither
should be duplicated.

On the deployed stack the world lives in `/opt/stacks/minecraft/paper-data`, and the two new
directories go beside it. With the minecraft stack scaled to zero:

```bash
cd /opt/stacks/minecraft
cp -a paper-data northeast-data
cp -a paper-data southeast-data
rm -rf {northeast,southeast}-data/world*/{playerdata,stats,advancements}
rm -f  {northeast,southeast}-data/plugins/Shards/mirror.json.gz
```

`cp -a` rather than `cp -r` so ownership and timestamps survive - the container runs as the same
uid it wrote the original world with. The deploy creates both directories if they are missing, so
do the copy before the first `update-server` run or stop the stack, copy, and start it again.

The proxy stores player data in the `shards` database, which the local stack creates for you. On a
deployed host, create it by hand before the first run.

### Private Config
Sensitive information is stored in a private repository, and required for Ansible deployment.
If you have access to this, you can get the submodule with `git submodule init` and `git submodule update`

To use this with the local docker-compose stack,
you can use `docker compose up -f docker-compose.yml -f docker-compose.private.yml` to merge the configurations.

Hot tip: If you use different SSH keys for your Civ GitHub account, you might use an SSH alias (`git clone git@civmc.github.com:...`).
If this is the case, you can clone the submodule by setting the`GIT_SSH_COMMAND`environment variable
to `ssh -i /path/to/your/private/key` before updating the submodule.

#### Using the console
For the minecraft servers and other interactive containers, you can attach to the console to run commands:

1. run `docker ps`
2. Note the name of the container. By default, randomly generated.
3. Run `docker attach <name>`, for example: `docker attach 81dcee85c1da`

## Licencing
This project and any subprojects not otherwise containing a licence file are licenced under the MIT licence.
Individual plugins may be subject to their own licences, please check the respective plugin directories for details.
