function Assert-LocalDemoTarget([string]$FixtureRun) {
    if ($FixtureRun) {
        if ($FixtureRun -notmatch '^[a-f0-9]{32}$') { throw 'Invalid fixture identifier' }
        $fixtureName = 'fh-official-local-demo-fixture-' + $FixtureRun
        $fixture = (& docker inspect $fixtureName 2>$null | ConvertFrom-Json)[0]
        if ($LASTEXITCODE -ne 0 -or -not $fixture -or
                $fixture.Name -ne "/$fixtureName" -or -not $fixture.State.Running -or
                $fixture.Config.Labels.'financial-helper.official-local-demo.fixture' -ne $FixtureRun -or
                -not $fixture.HostConfig.Tmpfs.'/var/lib/postgresql/data' -or
                @($fixture.Mounts).Count -ne 0 -or
                @($fixture.Config.Env) -notcontains 'POSTGRES_DB=fh_official_local_demo' -or
                @($fixture.Config.Env) -notcontains 'POSTGRES_USER=fh_official_demo' -or
                @($fixture.NetworkSettings.Ports.'5432/tcp' | Where-Object { $_ }).Count -ne 0) {
            throw 'Isolated local demo fixture ownership mismatch'
        }
        return $fixture.Id
    }
    $name = 'fh-official-local-demo'
    $volumeName = 'fh-official-local-demo-data'
    $container = (& docker inspect $name 2>$null | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or -not $container) { throw 'Dedicated local demo container not found' }
    $volume = (& docker volume inspect $volumeName 2>$null | ConvertFrom-Json)[0]
    if ($LASTEXITCODE -ne 0 -or -not $volume) { throw 'Dedicated local demo volume not found' }
    $owner = $volume.Labels.'financial-helper.official-local-demo.owner'
    $mount = @($container.Mounts)
    $binding = @($container.HostConfig.PortBindings.'5432/tcp')
    if ($owner -notmatch '^[a-f0-9]{32}$' -or $volume.Name -ne $volumeName -or
            $volume.Driver -ne 'local' -or $container.Name -ne "/$name" -or
            $container.Config.Labels.'financial-helper.official-local-demo.owner' -ne $owner -or
            $container.Config.Image -ne 'postgres:16-alpine' -or -not $container.State.Running -or
            $container.HostConfig.Tmpfs -or $mount.Count -ne 1 -or
            $mount[0].Type -ne 'volume' -or $mount[0].Name -ne $volumeName -or
            $mount[0].Destination -ne '/var/lib/postgresql/data' -or
            $binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or
            $binding[0].HostPort -ne '54765' -or
            @($container.Config.Env) -notcontains 'POSTGRES_DB=fh_official_local_demo' -or
            @($container.Config.Env) -notcontains 'POSTGRES_USER=fh_official_demo') {
        throw 'Dedicated local demo target ownership or database identity mismatch'
    }
    return $container.Id
}
