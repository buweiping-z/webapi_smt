namespace webapi.Data
{
    using Microsoft.EntityFrameworkCore;
    using webapi.Models;

    public class AppDbContext : DbContext
    {
        public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
        {
        }

        public DbSet<InspectionRecord> InspectionRecords { get; set; }
        public DbSet<InspectionTemplate> InspectionTemplates { get; set; }
        public DbSet<InspectionResult> InspectionResults { get; set; }
        public DbSet<InspectionSignature> InspectionSignatures { get; set; }
        public DbSet<InspectionUser> InspectionUsers { get; set; }
        public DbSet<QualifiedInspector> QualifiedInspectors { get; set; }
        public DbSet<Device> Devices { get; set; }

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);

            // 指定表名（兼容已有的表）
            modelBuilder.Entity<InspectionRecord>(entity =>
            {
                entity.ToTable("inspection_records");
                entity.Property(r => r.Frequency)
                    .HasColumnName("frequency")
                    .HasMaxLength(5)
                    .HasDefaultValue("日");
                entity.Property(r => r.PeriodKey)
                    .HasColumnName("period_key")
                    .HasMaxLength(10)
                    .HasDefaultValue("");
                entity.HasIndex(r => new { r.DeviceModel, r.Frequency, r.PeriodKey })
                    .IsUnique()
                    .HasDatabaseName("idx_records_period");
            });

            modelBuilder.Entity<InspectionTemplate>(entity =>
            {
                entity.ToTable("inspection_templates");
                entity.Property(t => t.Frequency)
                    .HasColumnName("frequency")
                    .HasMaxLength(5)
                    .HasDefaultValue("日");
                entity.Property(t => t.IsMandatory)
                    .HasColumnName("is_mandatory")
                    .HasDefaultValue(true);
            });

            modelBuilder.Entity<InspectionResult>()
                .ToTable("inspection_results");

            // 配置外键关系
            modelBuilder.Entity<InspectionResult>()
                .HasOne(r => r.InspectionRecord)
                .WithMany()
                .HasForeignKey(r => r.RecordId)
                .OnDelete(DeleteBehavior.Cascade);

            modelBuilder.Entity<InspectionUser>(entity =>
            {
                entity.ToTable("inspection_users");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.Username).HasColumnName("username");
                entity.Property(e => e.Password).HasColumnName("password");
                entity.Property(e => e.Role).HasColumnName("role");
                entity.Property(e => e.FullName).HasColumnName("full_name");
                entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            });

            modelBuilder.Entity<QualifiedInspector>(entity =>
            {
                entity.ToTable("qualified_inspectors");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.EmployeeId).HasColumnName("employee_id").HasMaxLength(9).IsRequired();
                entity.Property(e => e.LastName).HasColumnName("last_name").HasMaxLength(6).IsRequired();
                entity.HasIndex(e => e.EmployeeId)
                    .IsUnique()
                    .HasDatabaseName("idx_qualified_inspectors_employee_id");
            });

            modelBuilder.Entity<Device>(entity =>
            {
                entity.ToTable("devices");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.DeviceName).HasColumnName("device_name").HasMaxLength(100).IsRequired();
                entity.Property(e => e.DeviceModel).HasColumnName("device_model").HasMaxLength(50).IsRequired();
                entity.Property(e => e.DeviceLocation).HasColumnName("device_location").HasMaxLength(200);
                entity.Property(e => e.CreatedAt).HasColumnName("created_at").HasDefaultValueSql("CURRENT_TIMESTAMP");
                entity.HasIndex(e => e.DeviceModel).IsUnique().HasDatabaseName("idx_devices_device_model");
            });
        }
    }
}
